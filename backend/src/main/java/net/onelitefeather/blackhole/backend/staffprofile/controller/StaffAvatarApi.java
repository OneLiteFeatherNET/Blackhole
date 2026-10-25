package net.onelitefeather.blackhole.backend.staffprofile.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

public interface StaffAvatarApi {

    @Operation(
            summary = "A staff head",
            description = "The 64 px head of a linked staff account, by its keyed avatar key. The key reveals neither the "
                    + "Minecraft UUID nor the account. Served to players through the portal only; cached for a day.",
            operationId = "getStaffAvatar",
            tags = {"Public"}
    )
    @ApiResponse(responseCode = "200", description = "The head as PNG",
            content = @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "No head for this key; the client shows initials")
    @Get("/staff-avatar/{avatarKey}")
    HttpResponse<?> avatar(@PathVariable String avatarKey);
}
