package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.dto.PunishProfileDTO;
import net.onelitefeather.blackhole.backend.response.PunishProfileResponse;

@Controller(value = "/profile")
public class PunishmentProfileHandler {

    private final PunishmentProfileRepository repository;

    /**
     * Create a new PunishmentProfileHandler.
     *
     * @param repository the repository to use
     */
    @Inject
    public PunishmentProfileHandler(PunishmentProfileRepository repository) {
        this.repository = repository;
    }

    /**
     * Add a new punishment profile.
     *
     * @param profileEntity the profile to add
     * @return the added profile
     */
    @Operation(
            description = "Add a new profile for punishments",
            operationId = "addProfile",
            tags = {"PunishProfile"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "The profile which was added to the service",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
                            implementation = PunishProfileResponse.class
                    )
            )
    )
    @Validated
    @Post()
    public HttpResponse<PunishProfileResponse> add(@Body @Valid PunishProfileDTO profileEntity) {
        PunishmentProfileEntity entity = PunishmentProfileEntity.toEntity(profileEntity);
        PunishmentProfileEntity savedEntity = this.repository.save(entity);
        return HttpResponse.ok(savedEntity.toDTO());
    }

    /**
     * Get a punishment profile by the owner.
     *
     * @param profileEntity the profile to get
     * @return the profile
     */
    @Operation(
            description = "Update a punishment profile",
            operationId = "updateProfile",
            tags = {"PunishProfile"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "The updated profile",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
                            implementation = PunishProfileResponse.class
                    )
            )

    )
    @ApiResponse(
            responseCode = "400",
            description = "Error message when there is no profile linked to the given id",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
                            implementation = PunishProfileResponse.ErrorResponse.class
                    )
            )
    )
    @Validated
    @Post(value = "/update/{owner}")
    public HttpResponse<PunishProfileResponse> update(
            @Valid @Body PunishProfileDTO profileEntity,
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner
    ) {
        if (!profileEntity.owner().equals(owner)) {
            return HttpResponse.badRequest(new PunishProfileResponse.ErrorResponse("Owner in path and body do not match"));
        }
        PunishmentProfileEntity entity = PunishmentProfileEntity.toEntity(profileEntity);
        PunishmentProfileEntity savedEntity = this.repository.update(entity);
        return HttpResponse.ok(savedEntity.toDTO());
    }

    /**
     * Delete a punishment profile by the owner.
     *
     * @param owner the owner of the profile
     * @return the profile
     */
    @Operation(
            description = "Delete a punish profile via uuid",
            operationId = "removeProfile",
            tags = {"PunishProfile"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "The deleted profile",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
                            implementation = PunishProfileResponse.class
                    )
            )
    )
    @ApiResponse(
            responseCode = "400",
            description = "Error message when there is no profile linked to the given id",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
                            implementation = PunishProfileResponse.ErrorResponse.class
                    )
            )
    )
    @Validated
    @Delete(value = "/delete/{owner}")
    public HttpResponse<PunishProfileResponse> delete(
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner
    ) {
        PunishmentProfileEntity entity = this.repository.findById(owner).orElse(null);
        if (entity == null) {
            return HttpResponse.badRequest(new PunishProfileResponse.ErrorResponse("There is no profile linked to the given owner"));
        }
        this.repository.delete(entity);
        return HttpResponse.ok(entity.toDTO());
    }

    /**
     * Get all punishment profiles.
     *
     * @return a list of all punishment profiles
     */
    @Operation(
            description = "Get all existing punishment profiles",
            operationId = "getProfiles",
            tags = {"PunishProfile"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "The updated profile",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(
                            schema = @Schema(implementation = PunishProfileResponse.class),
                            arraySchema = @Schema(implementation = Page.class)
                    )
            )

    )
    @Get("/")
    public HttpResponse<Page<PunishProfileResponse>> getAll(Pageable pageable) {
        Page<PunishmentProfileEntity> entities = this.repository.findAll(pageable);
        return HttpResponse.ok(entities.map(PunishmentProfileEntity::toDTO));
    }

    /**
     * Get a punishment profile by the owner.
     *
     * @return a one-punishment profile by the owner
     */
    @Operation(
            description = "Get a profile by id",
            operationId = "getById",
            tags = {"PunishProfile"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "The profile which matches with the given id",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
                            implementation = PunishProfileDTO.class
                    )
            )
    )
    @ApiResponse(
            responseCode = "404",
            description = "Error message when there is no profile linked to the given id",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
                            implementation = PunishProfileResponse.ErrorResponse.class
                    )
            )
    )
    @Get("/{owner}")
    public HttpResponse<PunishProfileResponse> getById(
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner
    ) {
        var entity = this.repository.findById(owner).orElse(null);
        if (entity == null) {
            return HttpResponse.notFound(new PunishProfileResponse.ErrorResponse("There is no profile linked to the given owner"));
        }
        PunishmentEntity activeBan = entity.getActiveBan();
        PunishmentEntity activeChatBan = entity.getActiveChatBan();
        if (activeBan != null && activeBan.toDTO().expirationDate() < System.currentTimeMillis()) {
            entity.setActiveBan(null);
            entity.getHistory().add(activeBan);
        }
        if (activeChatBan != null && activeChatBan.toDTO().expirationDate() < System.currentTimeMillis()) {
            entity.setActiveChatBan(null);
            entity.getHistory().add(activeChatBan);
        }
        entity = this.repository.update(entity);
        return HttpResponse.ok(entity.toDTO());
    }
}
