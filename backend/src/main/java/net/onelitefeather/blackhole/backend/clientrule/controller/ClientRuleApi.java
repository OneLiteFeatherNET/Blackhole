package net.onelitefeather.blackhole.backend.clientrule.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleDTO;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.util.List;

public interface ClientRuleApi {

    @Operation(
            summary = "Create a client rule",
            description = "Creates an enabled rule. The action defaults to NOTIFY and the scope to GLOBAL. Brand, channel and mod "
                    + "values are chosen by the client and can be faked, so a TEMPLATE action on them needs acknowledgedSpoofable. "
                    + "Admins only.",
            operationId = "createClientRule",
            tags = {"ClientRule"}
    )
    @ApiResponse(responseCode = "200", description = "Rule created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "Invalid rule, unknown section or template",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleDTO.Error.class)))
    @ApiResponse(responseCode = "409", description = "A rule with this key already exists",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleDTO.Error.class)))
    @Post("/")
    HttpResponse<ClientRuleDTO> create(@Valid @Body ClientRuleDTO.CreateRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Update a client rule",
            description = "Replaces every editable field of the rule, including whether it is enabled. The body key must equal the path key. "
                    + "Admins only.",
            operationId = "updateClientRule",
            tags = {"ClientRule"}
    )
    @ApiResponse(responseCode = "200", description = "Rule updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleDTO.Response.class)))
    @ApiResponse(responseCode = "400", description = "Invalid rule, unknown section or template",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleDTO.Error.class)))
    @ApiResponse(responseCode = "404", description = "Rule not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleDTO.Error.class)))
    @Put("/{key}")
    HttpResponse<ClientRuleDTO> update(@PathVariable String key, @Valid @Body ClientRuleDTO.UpdateRequest request, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "Delete a client rule",
            description = "Removes the rule with its patterns, servers and sections. Admins only.",
            operationId = "deleteClientRule",
            tags = {"ClientRule"}
    )
    @ApiResponse(responseCode = "200", description = "The removed rule",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "Rule not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleDTO.Error.class)))
    @Delete("/{key}")
    HttpResponse<ClientRuleDTO> delete(@PathVariable String key, @Parameter(hidden = true) Actor actor);

    @Operation(
            summary = "List client rules",
            description = "All rules, enabled or not, sorted by key. Refreshes the enabled rules in the shared cache that proxies read.",
            operationId = "listClientRules",
            tags = {"ClientRule"}
    )
    @ApiResponse(responseCode = "200", description = "The rules",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = ClientRuleDTO.Response.class))))
    @Get("/")
    HttpResponse<List<ClientRuleDTO.Response>> list();

    @Operation(
            summary = "Get a client rule",
            operationId = "getClientRule",
            tags = {"ClientRule"}
    )
    @ApiResponse(responseCode = "200", description = "The rule",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleDTO.Response.class)))
    @ApiResponse(responseCode = "404", description = "Rule not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ClientRuleDTO.Error.class)))
    @Get("/{key}")
    HttpResponse<ClientRuleDTO> get(@PathVariable String key);
}
