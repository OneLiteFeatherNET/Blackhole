package net.onelitefeather.blackhole.backend.discord.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.UUID;

/**
 * The staff lock of an appeal ticket's channel: a staff member locks it (the freeze carries source {@code STAFF}) or unlocks it.
 * Each variant is a record; a refusal is {@link Error}, never an exception.
 */
@Schema(name = "StaffTicket", description = "The staff lock of an appeal ticket channel")
public sealed interface StaffTicketDTO {

    /**
     * @param frozen     {@code true} to lock the channel, {@code false} to unlock it
     * @param onBehalfOf the staff member's oid, sent by the Discord bot only; the panel sends none
     */
    @Schema(name = "StaffTicketFreezeRequest")
    @Serdeable
    @ReflectiveAccess
    record FreezeRequest(boolean frozen, @Nullable UUID onBehalfOf) implements StaffTicketDTO {
    }

    /**
     * @param frozen       the state afterwards
     * @param freezeSource {@code STAFF} or {@code LEAK} while frozen, {@code null} when not
     */
    @Schema(name = "StaffTicketResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(boolean frozen, @Nullable String freezeSource) implements StaffTicketDTO {
    }

    /**
     * @param code the stable code of a refusal that has one ({@code FREEZE_BY_LEAK}), otherwise {@code null}
     */
    @Schema(name = "StaffTicketError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind, @Nullable String code) implements StaffTicketDTO, ErrorResponse {
    }
}
