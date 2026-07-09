package net.onelitefeather.blackhole.backend.imports.controller;

import net.onelitefeather.blackhole.backend.imports.dto.VanillaImportResultDTO;
import net.onelitefeather.blackhole.backend.imports.service.VanillaImportService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.core.version.annotation.Version;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;

import java.io.IOException;

/**
 * Bulk-imports vanilla Minecraft ban lists into Blackhole. Deliberately an admin-only,
 * hidden-from-public-spec endpoint (not a standalone CLI tool) so imported bans go through the
 * same repository/event path as any other write and stay auditable.
 */
@Version(ApiVersion.V1)
@Controller("/admin/import")
public class VanillaImportController {

    private final VanillaImportService importService;

    @Inject
    public VanillaImportController(VanillaImportService importService) {
        this.importService = importService;
    }

    @Operation(hidden = true)
    @Post(value = "/vanilla", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<?> importVanilla(
            @Part("bannedPlayers") CompletedFileUpload bannedPlayers,
            @Part("bannedIps") @Nullable CompletedFileUpload bannedIps,
            @QueryValue(defaultValue = "false") boolean dryRun
    ) {
        try {
            byte[] playersBytes = bannedPlayers.getBytes();
            byte[] ipsBytes = bannedIps != null ? bannedIps.getBytes() : null;
            VanillaImportResultDTO result = this.importService.importVanillaBans(playersBytes, ipsBytes, dryRun);
            return HttpResponse.ok(result);
        } catch (IOException e) {
            return HttpResponse.badRequest("Failed to read uploaded file(s): " + e.getMessage());
        }
    }
}
