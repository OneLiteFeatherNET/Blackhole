package net.onelitefeather.blackhole.backend.profile;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileRequestDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps a whole-profile write from forging verification. {@code recordedByOid} is only ever set by
 * the server from a token, so whatever a client sends in a profile body is discarded: an entry the
 * server already stores keeps its stored value, unless the client changed its {@code source}, and a
 * new entry has none. An entry without {@code recordedByOid} is shown as unverified.
 */
@Singleton
public class ProfileAttributionGuard {

    private final PunishmentRepository punishments;

    public ProfileAttributionGuard(PunishmentRepository punishments) {
        this.punishments = punishments;
    }

    /** The request with every entry's {@code recordedByOid} replaced by what the server knows. */
    public PunishProfileRequestDTO sanitize(PunishProfileRequestDTO request) {
        Map<String, PunishEntryDTO> active = null;
        if (request.active() != null) {
            active = new LinkedHashMap<>();
            for (Map.Entry<String, PunishEntryDTO> entry : request.active().entrySet()) {
                active.put(entry.getKey(), sanitize(entry.getValue()));
            }
        }
        List<PunishEntryDTO> history = request.history() == null ? null : request.history().stream().map(this::sanitize).toList();
        return new PunishProfileRequestDTO(request.owner(), active, history, request.metaData());
    }

    private PunishEntryDTO sanitize(PunishEntryDTO entry) {
        PunishmentEntity stored = entry.identifier() == null ? null : this.punishments.findById(entry.identifier()).orElse(null);
        if (stored == null || !Objects.equals(stored.getSource(), entry.source())) {
            return entry.withRecordedByOid(null);
        }
        return entry.withRecordedByOid(stored.getRecordedByOid());
    }
}
