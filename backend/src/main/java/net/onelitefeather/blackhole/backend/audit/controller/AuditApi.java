package net.onelitefeather.blackhole.backend.audit.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.onelitefeather.blackhole.backend.audit.dto.AuditDTO;

import java.util.UUID;

/**
 * The OpenAPI contract of {@code /admin/audit}. The controller implements it.
 */
public interface AuditApi {

    @Operation(
            summary = "List the staff audit log",
            description = "Staff actions, newest first, one cursor page at a time. Filter by action, actor or target. "
                    + "Read only: an entry is never edited or deleted here. The cursor is opaque; pass the nextCursor "
                    + "of the previous page.",
            operationId = "listStaffAudit",
            tags = {"Audit"}
    )
    @ApiResponse(responseCode = "200", description = "A page of audit entries",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuditDTO.Page.class)))
    @ApiResponse(responseCode = "400", description = "Invalid limit or cursor",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuditDTO.Error.class)))
    @Get
    HttpResponse<AuditDTO> list(
            @Nullable @QueryValue Integer limit,
            @Nullable @QueryValue String cursor,
            @Nullable @QueryValue String action,
            @Nullable @QueryValue UUID actorOid,
            @Nullable @QueryValue String targetRef
    );
}
