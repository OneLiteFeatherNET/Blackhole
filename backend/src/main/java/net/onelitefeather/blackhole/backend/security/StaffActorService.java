package net.onelitefeather.blackhole.backend.security;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.audit.StaffAuditStore;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRepository;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolRedaction;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.dto.StaffActorDTO;
import net.onelitefeather.blackhole.backend.staffprofile.StaffProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Erasure of staff identity data. Anonymizing clears the display name, removes the person's Minecraft link and Discord link (a leaving person must not stay attributable by UUID) and keeps the {@code oid}, so
 * history still points at the same principal; once the Entra account is deleted the oid no longer
 * resolves to a person. The action is audited with the admin's own verified {@code oid}.
 */
@Singleton
public class StaffActorService {

    private static final Logger LOG = LoggerFactory.getLogger(StaffActorService.class);

    static final String ANONYMIZED_EVENT = "staff-actor.anonymized";

    private final StaffActorRepository actors;
    private final DomainEventPublisher events;
    private final StaffLinkService links;
    private final DiscordLinkService discordLinks;

    private final ProtocolRedaction protocols;
    private final StaffProfileService profiles;
    private final CaseEventRepository caseEvents;
    private final StaffAuditStore audit;

    public StaffActorService(StaffActorRepository actors, DomainEventPublisher events, StaffLinkService links, DiscordLinkService discordLinks,
                             ProtocolRedaction protocols, StaffProfileService profiles, CaseEventRepository caseEvents, StaffAuditStore audit) {
        this.audit = audit;
        this.protocols = protocols;
        this.actors = actors;
        this.events = events;
        this.links = links;
        this.discordLinks = discordLinks;
        this.profiles = profiles;
        this.caseEvents = caseEvents;
    }

    /** Every known principal, most recently seen first. */
    public StaffActorDTO list() {
        return new StaffActorDTO.ListResponse(this.actors.findAll().stream()
                .sorted(Comparator.comparingLong(StaffActorEntity::getLastSeenAt).reversed())
                .map(actor -> new StaffActorDTO.Item(
                        actor.getOid(), actor.getKind(), actor.getDisplayName(), actor.getFirstSeenAt(), actor.getLastSeenAt()))
                .toList());
    }

    /**
     * Clears the display name of {@code oid}. Repeating it is harmless.
     *
     * @param admin the verified caller, recorded in the log and the {@code staff-actor.anonymized} event
     * @return the {@link StaffActorDTO.Response}, or a {@link StaffActorDTO.Error} (404) for an unknown principal
     */
    public StaffActorDTO anonymize(UUID oid, Actor admin) {
        Optional<StaffActorEntity> found = this.actors.findById(oid);
        if (found.isEmpty()) {
            return new StaffActorDTO.Error("there is no such staff actor", ErrorKind.NOT_FOUND);
        }
        StaffActorEntity actor = found.get();
        actor.setDisplayName(null);
        this.actors.update(actor);
        this.links.removeOnAnonymize(oid, admin);
        this.discordLinks.removeOnAnonymize(oid);
        // Clears the display name and the avatar key; the head itself is deleted with the link above.
        this.profiles.anonymize(oid);
        // The name snapshots of the messages go before the protocols are regenerated, so none of them can show it again.
        this.caseEvents.clearStaffNameSnapshots(oid);
        // The audit's name snapshots go with them; the oid stays on every row.
        this.audit.clearActorName(oid);
        // The name is cleared first, so a regenerated protocol cannot show it again; never throws.
        this.protocols.afterStaffAnonymized(oid);
        LOG.info("Staff actor {} anonymized by {}", oid, admin.oid());
        this.events.publish(ANONYMIZED_EVENT, Map.of("oid", oid.toString(), "anonymizedBy", admin.oid().toString()));
        return new StaffActorDTO.Response(oid);
    }
}
