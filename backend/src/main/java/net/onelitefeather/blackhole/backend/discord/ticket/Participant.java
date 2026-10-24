package net.onelitefeather.blackhole.backend.discord.ticket;

import io.micronaut.core.annotation.Nullable;

import java.util.UUID;

/**
 * A conversation partner of one case. The Discord user id and the name are personal data kept only here;
 * erasing the participant nulls them and sets {@code erasedAt}. Events refer to {@link #id()} only.
 */
public record Participant(UUID id, String punishmentIdentifier, ParticipantKind kind, @Nullable String discordUserId,
                          @Nullable String displayName, boolean bot, long createdAt, @Nullable Long erasedAt) {

    public boolean erased() {
        return this.erasedAt != null;
    }
}
