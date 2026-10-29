package net.onelitefeather.blackhole.backend.exemption;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.exemption.dto.ExemptionDTO;
import net.onelitefeather.blackhole.backend.imports.UUIDHasher;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.utils.OwnerHash;
import net.onelitefeather.blackhole.backend.security.StaffActorEntity;
import net.onelitefeather.blackhole.backend.security.StaffActorRepository;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The exemption projection: which hashed owners are exempt, from linked staff and from the proxy's permission reports, and at which
 * rank they are protected. The projection holds no raw UUID; a {@code LINKED_STAFF} row is written when a link is confirmed and
 * removed when it ends (see {@code StaffLinkService}).
 */
@Singleton
public class ExemptionService {

    static final String LINKED_STAFF = "LINKED_STAFF";
    static final String PERMISSION = "PERMISSION";
    /** The flag of {@code blackhole.exempt}: every section. */
    static final String ALL_SECTIONS = "*";

    /**
     * The owner a Minecraft UUID is keyed by: the SHA-512 of its canonical text, the same value Velocity computes for the player.
     */
    public static String ownerOf(UUID minecraftUuid) {
        return UUIDHasher.hash(minecraftUuid);
    }

    private final PlayerExemptionRepository rows;
    private final StaffActorRepository actors;
    private final Clock clock;
    private final boolean enabled;

    public ExemptionService(PlayerExemptionRepository rows, StaffActorRepository actors, Clock clock,
                            @Value("${blackhole.exemption.enabled:true}") boolean enabled) {
        this.rows = rows;
        this.actors = actors;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** The emergency switch: when off, no player is exempt and punishments behave as before this feature. */
    public boolean enabled() {
        return this.enabled;
    }

    /**
     * The protection of an owner for a section. Empty when the owner is not exempt there.
     *
     * @param sectionKey the section of the punishment, or null when it has none (a network kick): any permission counts then
     */
    public Optional<Protection> protectionOf(String owner, @Nullable String sectionKey) {
        if (!this.enabled) {
            return Optional.empty();
        }
        List<PlayerExemptionEntity> applicable = this.rows.findByIdOwner(owner).stream()
                .filter(row -> covers(row, sectionKey))
                .toList();
        if (applicable.isEmpty()) {
            return Optional.empty();
        }
        ExemptionRank rank = ExemptionRank.VIEWER;
        List<String> sources = new ArrayList<>();
        for (PlayerExemptionEntity row : applicable) {
            String source = row.getId().getSource();
            sources.add(source);
            ExemptionRank own = LINKED_STAFF.equals(source) ? staffRank(row.getStaffOid()) : ExemptionRank.MODERATOR;
            if (own.outranks(rank)) {
                rank = own;
            }
        }
        return Optional.of(new Protection(rank, sources));
    }

    /** Whether the owner is exempt in the section (any rank). */
    public boolean isExempt(String owner, @Nullable String sectionKey) {
        return protectionOf(owner, sectionKey).isPresent();
    }

    /**
     * The rank of a staff member: the highest role of their last recorded snapshot, MODERATOR when none was recorded.
     */
    public ExemptionRank staffRank(@Nullable UUID oid) {
        if (oid == null) {
            return ExemptionRank.MODERATOR;
        }
        return this.actors.findById(oid)
                .map(StaffActorEntity::getRoles)
                .flatMap(ExemptionRank::highest)
                .orElse(ExemptionRank.MODERATOR);
    }

    /** Records that the owner's account is linked to {@code staffOid}. Called when a link is confirmed. */
    public void linkStaff(String owner, UUID staffOid) {
        PlayerExemptionId id = new PlayerExemptionId(owner, LINKED_STAFF);
        PlayerExemptionEntity row = new PlayerExemptionEntity(id, null, staffOid, this.clock.millis());
        this.rows.findById(id).ifPresentOrElse(existing -> {
            existing.setStaffOid(staffOid);
            existing.setReportedAt(row.getReportedAt());
            this.rows.update(existing);
        }, () -> this.rows.save(row));
    }

    /** Removes the linked-staff row of the owner, when the link ends. A no-op when there is none. */
    public void unlinkStaff(String owner) {
        this.rows.findById(new PlayerExemptionId(owner, LINKED_STAFF)).ifPresent(this.rows::delete);
    }

    /**
     * Replaces the permission rows of the owner with what the proxy reported. Empty flags remove them. Linked-staff rows are untouched.
     *
     * @param flags {@code *} for {@code blackhole.exempt}, or section keys for {@code blackhole.exempt.<section>}; the caller has
     *              validated them
     * @return the status after the report
     */
    public ExemptionDTO report(ExemptionDTO.ReportRequest request) {
        if (!OwnerHash.isValid(request.owner())) {
            return new ExemptionDTO.Error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        String owner = request.owner();
        List<String> flags = request.flags() == null ? List.of() : request.flags();
        PlayerExemptionId id = new PlayerExemptionId(owner, PERMISSION);
        if (flags == null || flags.isEmpty()) {
            this.rows.findById(id).ifPresent(this.rows::delete);
        } else {
            String sections = flags.contains(ALL_SECTIONS) ? ALL_SECTIONS
                    : String.join(",", new TreeSet<>(flags));
            this.rows.findById(id).ifPresentOrElse(existing -> {
                existing.setSections(sections);
                existing.setReportedAt(this.clock.millis());
                this.rows.update(existing);
            }, () -> this.rows.save(new PlayerExemptionEntity(id, sections, null, this.clock.millis())));
        }
        return statusOf(owner);
    }

    /** The exemption of an owner as the read answers it: no UUID, no name. */
    public ExemptionDTO status(String owner) {
        if (!OwnerHash.isValid(owner)) {
            return new ExemptionDTO.Error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        return statusOf(owner);
    }

    private ExemptionDTO.StatusResponse statusOf(String owner) {
        List<PlayerExemptionEntity> all = this.rows.findByIdOwner(owner);
        if (all.isEmpty()) {
            return new ExemptionDTO.StatusResponse(false, List.of(), List.of(), null);
        }
        List<String> sources = all.stream().map(row -> row.getId().getSource())
                .sorted(Comparator.naturalOrder()).toList();
        List<String> sections = all.stream()
                .filter(row -> PERMISSION.equals(row.getId().getSource()) && row.getSections() != null)
                .flatMap(row -> List.of(row.getSections().split(",")).stream())
                .distinct().sorted().toList();
        Optional<Protection> protection = protectionOf(owner, null);
        return new ExemptionDTO.StatusResponse(protection.isPresent(), sources, sections,
                protection.map(p -> p.rank().name()).orElse(null));
    }

    private static boolean covers(PlayerExemptionEntity row, @Nullable String sectionKey) {
        if (LINKED_STAFF.equals(row.getId().getSource())) {
            return true;
        }
        if (sectionKey == null || row.getSections() == null) {
            return row.getSections() != null;
        }
        return List.of(row.getSections().split(",")).contains(ALL_SECTIONS)
                || List.of(row.getSections().split(",")).contains(sectionKey);
    }

    /**
     * What protects an owner in a section: the rank the punishing actor must outrank, and the sources that grant it.
     *
     * @param rank    the highest rank of the applicable sources
     * @param sources {@code LINKED_STAFF} and/or {@code PERMISSION}
     */
    public record Protection(ExemptionRank rank, List<String> sources) {
    }
}
