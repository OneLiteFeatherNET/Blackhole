package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.imports.VanillaImportResultDTO;
import net.onelitefeather.blackhole.backend.imports.VanillaImportService;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.security.TenantContext;

import java.io.IOException;
import java.util.UUID;

/**
 * Bulk-imports vanilla Minecraft ban lists for a tenant switching to Blackhole. Deliberately an
 * admin-only, hidden-from-public-spec endpoint (not a standalone CLI tool) so imported bans go
 * through the same repository/event path as any other write and stay auditable.
 */
@Secured({Roles.PLATFORM_ADMIN, Roles.TENANT_ADMIN})
@Controller(ApiVersion.V1 + "/admin/import")
public class VanillaImportController {

    private final VanillaImportService importService;
    private final TenantContext tenantContext;

    @Inject
    public VanillaImportController(VanillaImportService importService, TenantContext tenantContext) {
        this.importService = importService;
        this.tenantContext = tenantContext;
    }

    @Operation(hidden = true)
    @Post(value = "/vanilla/{tenantId}", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<?> importVanilla(
            UUID tenantId,
            @Part("bannedPlayers") CompletedFileUpload bannedPlayers,
            @Part("bannedIps") @Nullable CompletedFileUpload bannedIps,
            @QueryValue(defaultValue = "false") boolean dryRun
    ) {
        this.tenantContext.requireTenantAccess(tenantId);
        try {
            byte[] playersBytes = bannedPlayers.getBytes();
            byte[] ipsBytes = bannedIps != null ? bannedIps.getBytes() : null;
            VanillaImportResultDTO result = this.importService.importVanillaBans(tenantId, playersBytes, ipsBytes, dryRun);
            return HttpResponse.ok(result);
        } catch (IOException e) {
            return HttpResponse.badRequest("Failed to read uploaded file(s): " + e.getMessage());
        }
    }
}
