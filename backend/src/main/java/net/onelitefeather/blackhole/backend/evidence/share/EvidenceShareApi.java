package net.onelitefeather.blackhole.backend.evidence.share;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.util.UUID;

/**
 * Sharing evidence with the appealing player. A case is addressed by the punishment's identifier or short id. The acting staff member is
 * the caller of the verified token and the confirming {@code oid} of a share. Nothing in a response carries evidence content other than the
 * preview, which is exactly what the player would see.
 */
public interface EvidenceShareApi {

    @Operation(
            summary = "Preview what a share would show",
            description = "Returns exactly the lines and labels (or the image facts) the player would see for a selection of a chat capture: "
                    + "the appealing speaker as You, the others as Player 2, Player 3, times as offsets, plain text. Changes nothing.",
            operationId = "previewEvidenceShare",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The preview",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Preview.class)))
    @ApiResponse(responseCode = "400", description = "BAD_SELECTION: no lines, more than the maximum, a line twice or outside, no valid appealing speaker",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment or evidence",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "NOT_VALID: the evidence is lifted or revoked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Error.class)))
    @ApiResponse(responseCode = "422", description = "NOT_SHAREABLE, or PII_REJECTED (a line holds a UUID or an IP; only its index is named)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Error.class)))
    @Post("/{reference}/evidence-shares/preview")
    HttpResponse<EvidenceShareDTO> preview(@PathVariable String reference, @Body EvidenceShareDTO.PreviewRequest request);

    @Operation(
            summary = "Share evidence with the player",
            description = "Shows one chat capture (selected lines, at most 20) or one team screenshot to the player of the case's appeal. "
                    + "confirmedMinimised must be true. The share is one case event (evidence.shared) without content, and can be revoked. "
                    + "An identical active share answers the existing one with repeated: true.",
            operationId = "shareEvidence",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The share",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Share.class)))
    @ApiResponse(responseCode = "400", description = "NOT_CONFIRMED or BAD_SELECTION",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment or evidence",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "NO_APPEAL, FROZEN (Ban ID marked leaked) or NOT_VALID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Error.class)))
    @ApiResponse(responseCode = "422", description = "NOT_SHAREABLE (anticheat, replay, link, transcript, video, a player's own file) or PII_REJECTED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Error.class)))
    @Post("/{reference}/evidence-shares")
    HttpResponse<EvidenceShareDTO> share(@PathVariable String reference, @Body EvidenceShareDTO.CreateRequest request,
                                         @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "List the shares of a case",
            description = "Lists the shares oldest first with their state: ACTIVE, REVOKED, or UNAVAILABLE when the evidence was deleted.",
            operationId = "listEvidenceShares",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The shares",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.ShareList.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Error.class)))
    @Get("/{reference}/evidence-shares")
    HttpResponse<EvidenceShareDTO> list(@PathVariable String reference);

    @Operation(
            summary = "Revoke a share",
            description = "Withdraws a share at once: the player's next read shows an empty card and the file route answers 404. It cannot take "
                    + "back what the player already saw or saved. Revoking again changes nothing and answers the share as revoked.",
            operationId = "revokeEvidenceShare",
            tags = {"Case"}
    )
    @ApiResponse(responseCode = "200", description = "The share, revoked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Share.class)))
    @ApiResponse(responseCode = "404", description = "Unknown punishment or share",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EvidenceShareDTO.Error.class)))
    @Post("/{reference}/evidence-shares/{shareId}/revoke")
    HttpResponse<EvidenceShareDTO> revoke(@PathVariable String reference, @PathVariable UUID shareId,
                                          @Body EvidenceShareDTO.RevokeRequest request, @Parameter(hidden = true) Actor actor);
}
