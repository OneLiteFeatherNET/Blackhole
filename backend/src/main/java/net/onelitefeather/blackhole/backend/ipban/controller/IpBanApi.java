package net.onelitefeather.blackhole.backend.ipban.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.ipban.dto.IpBanDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

public interface IpBanApi {

    @Operation(
            summary = "Ban an IP address or range",
            description = "Holds one IPv4 or IPv6 address, or a CIDR range, at the firewall, which pulls it from the feeds. A moderator "
                    + "may ban for at most blackhole.ipban.moderator-max-duration; an administrator may ban permanently. A range "
                    + "wider than /16 (IPv4) or /32 (IPv6), and any protected network, is refused.",
            operationId = "createIpBan",
            tags = {"IP bans"}
    )
    @ApiResponse(responseCode = "201", description = "The ban was stored; warnings name a wider active ban that covers it",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "Invalid address, reason, source or expiry",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.Error.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN_DURATION: a moderator asked for a permanent or too long ban",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "ALREADY_BANNED: an active ban already holds this range",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.Error.class)))
    @ApiResponse(responseCode = "422", description = "IPBAN_RANGE_TOO_WIDE or IPBAN_PROTECTED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.Error.class)))
    @Post
    HttpResponse<IpBanDTO> create(@Valid @Body IpBanDTO.CreateRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "List the newest IP bans",
            description = "The newest IP bans, active or not, up to 200. Shows the clear address: staff only.",
            operationId = "listIpBans",
            tags = {"IP bans"}
    )
    @ApiResponse(responseCode = "200", description = "The bans",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.Listing.class)))
    @Get
    HttpResponse<IpBanDTO> list(@QueryValue(defaultValue = "100") int limit);

    @Operation(
            summary = "The active IP bans for the proxies",
            description = "Every ban that is not lifted and not expired. The proxy keeps this list in memory and checks each "
                    + "login against it. Carries no actor and no case. Proxy tokens only.",
            operationId = "listActiveIpBans",
            tags = {"IP bans"}
    )
    @ApiResponse(responseCode = "200", description = "The active bans",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.ActiveList.class)))
    @Get("/active")
    HttpResponse<IpBanDTO> active();

    @Operation(
            summary = "Get one IP ban",
            operationId = "getIpBan",
            tags = {"IP bans"}
    )
    @ApiResponse(responseCode = "200", description = "The ban",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "No such ban",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.Error.class)))
    @Get("/{id}")
    HttpResponse<IpBanDTO> get(@PathVariable String id);

    @Operation(
            summary = "Lift an IP ban",
            description = "Lifts one active ban with a mandatory reason. A player's punishment is never changed, and lifting a "
                    + "punishment never lifts an IP ban.",
            operationId = "liftIpBan",
            tags = {"IP bans"}
    )
    @ApiResponse(responseCode = "200", description = "The lifted ban",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "The reason is missing or longer than 200 characters",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "No such ban",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "The ban is not active",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IpBanDTO.Error.class)))
    @Post("/{id}/lift")
    HttpResponse<IpBanDTO> lift(@PathVariable String id, @Valid @Body IpBanDTO.LiftRequest request,
                                @Parameter(hidden = true) Actor actor);
}
