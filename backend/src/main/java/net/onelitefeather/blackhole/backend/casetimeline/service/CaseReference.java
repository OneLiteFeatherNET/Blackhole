package net.onelitefeather.blackhole.backend.casetimeline.service;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentShortId;

import java.util.List;
import java.util.Optional;

/**
 * Finds the punishment a case route names: by its full identifier, or by its legacy short id when that
 * matches exactly one. A Ban ID is never a reference; the short id lookup is untouched by it.
 */
final class CaseReference {

    /** The punishment, or why there is none ({@code kind} and {@code message}). */
    record Found(@Nullable PunishmentEntity punishment, @Nullable CaseErrorKind kind, @Nullable String message) {

        boolean failed() {
            return this.kind != null;
        }

        static Found of(PunishmentEntity punishment) {
            return new Found(punishment, null, null);
        }

        static Found failure(CaseErrorKind kind, String message) {
            return new Found(null, kind, message);
        }
    }

    private final PunishmentRepository punishments;

    CaseReference(PunishmentRepository punishments) {
        this.punishments = punishments;
    }

    Found resolve(@Nullable String reference) {
        if (reference == null || reference.isBlank()) {
            return Found.failure(CaseErrorKind.VALIDATION, "a punishment is required");
        }
        Optional<PunishmentEntity> byId = this.punishments.findById(reference.strip());
        if (byId.isPresent()) {
            return Found.of(byId.get());
        }
        Optional<String> key = PunishmentShortId.parse(reference);
        if (key.isEmpty()) {
            return Found.failure(CaseErrorKind.NOT_FOUND, "there is no such punishment");
        }
        List<PunishmentEntity> matches = this.punishments.findByShortId(key.get());
        if (matches.isEmpty()) {
            return Found.failure(CaseErrorKind.NOT_FOUND, "there is no punishment with the short id " + PunishmentShortId.display(key.get()));
        }
        if (matches.size() > 1) {
            String candidates = String.join(", ", matches.stream().map(PunishmentEntity::getIdentifier).toList());
            return Found.failure(CaseErrorKind.CONFLICT, "the short id " + PunishmentShortId.display(key.get())
                    + " matches several punishments, use the full identifier: " + candidates);
        }
        return Found.of(matches.getFirst());
    }
}
