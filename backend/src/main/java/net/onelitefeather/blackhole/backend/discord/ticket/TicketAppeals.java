package net.onelitefeather.blackhole.backend.discord.ticket;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;

import java.util.UUID;

/**
 * The appeal a ticket is opened for: the one that is open, or a new one filed under the portal's rules (the eligibility checklist is
 * evaluated first and an attempt that is not eligible is <b>not</b> recorded). The seam keeps {@link TicketService} free of the
 * eligibility and persistence machinery.
 */
public interface TicketAppeals {

    /** What happened. */
    sealed interface Outcome {

        /** An appeal was already open; the ticket attaches to it. */
        record Existing(UUID appealId) implements Outcome {
        }

        /** This call filed the appeal. */
        record Filed(UUID appealId) implements Outcome {
        }

        /** The player may not file yet; nothing was recorded. */
        record NotYet(long availableFrom) implements Outcome {
        }

        /** No appeal is open and no statement was given. */
        record StatementMissing() implements Outcome {
        }

        /** The statement breaks the message rules (length, UUID, IP, Ban ID). */
        record StatementInvalid(String reason) implements Outcome {
        }
    }

    /**
     * @param statement the player's statement, {@code null} when none was given
     */
    Outcome attach(PunishmentEntity punishment, @Nullable String statement);
}
