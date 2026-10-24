package net.onelitefeather.blackhole.backend.casetimeline.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseParticipantDTO;

import java.util.UUID;

/** Erasure of the conversation participants of a case (the Discord users of its tickets). */
public interface CaseParticipantApi {

    @Operation(
            summary = "Erase a conversation participant",
            description = "Removes the Discord user id and the display name of the participant and marks it erased. The timeline keeps its structure with "
                    + "an anonymous author and its timestamps; the stored protocols are purged and regenerated. Message text is redacted separately. "
                    + "Erasing an erased participant changes nothing.",
            operationId = "eraseCaseParticipant",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The participant is erased",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseParticipantDTO.Erased.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment or participant",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseParticipantDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The short id matches several punishments",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CaseParticipantDTO.Error.class)))
    @Post("/{reference}/participants/{participantId}/erase")
    HttpResponse<CaseParticipantDTO> erase(@PathVariable String reference, @PathVariable UUID participantId);
}
