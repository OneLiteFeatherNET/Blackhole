package net.onelitefeather.blackhole.backend.profile;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEntity;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.utils.PunishmentExpiry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Version(ApiVersion.V1)
@Controller("/profile")
public class ProfileController {

    private final PunishmentProfileRepository repository;
    private final DomainEventPublisher eventPublisher;
    private final ProfileCache profileCache;
    private final CacheInvalidationPublisher cacheInvalidationPublisher;

    /**
     * Create a new ProfileController.
     *
     * @param repository                 the repository to use
     * @param eventPublisher             publishes domain events for successful writes
     * @param profileCache               the hot-path profile-by-owner read cache
     * @param cacheInvalidationPublisher invalidates the cache locally and across replicas on writes
     */
    @Inject
    public ProfileController(
            PunishmentProfileRepository repository,
            DomainEventPublisher eventPublisher,
            ProfileCache profileCache,
            CacheInvalidationPublisher cacheInvalidationPublisher
    ) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.profileCache = profileCache;
        this.cacheInvalidationPublisher = cacheInvalidationPublisher;
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
    @Post("/")
    public HttpResponse<PunishProfileResponse> add(@Body @Valid PunishProfileRequestDTO profileEntity) {
        PunishmentProfileEntity entity = PunishmentProfileEntity.toEntity(profileEntity);
        PunishmentProfileEntity savedEntity = this.repository.save(entity);
        this.cacheInvalidationPublisher.invalidate(profileEntity.owner());
        this.eventPublisher.publish("profile.created", Map.of(
                "owner", profileEntity.owner()
        ));
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
            @Valid @Body PunishProfileRequestDTO profileEntity,
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner
    ) {
        if (!profileEntity.owner().equals(owner)) {
            return HttpResponse.badRequest(new PunishProfileResponse.ErrorResponse("Owner in path and body do not match"));
        }
        PunishmentProfileEntity entity = PunishmentProfileEntity.toEntity(profileEntity);
        PunishmentProfileEntity savedEntity = this.repository.update(entity);
        this.cacheInvalidationPublisher.invalidate(owner);
        this.eventPublisher.publish("profile.updated", Map.of(
                "owner", owner
        ));
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
        this.cacheInvalidationPublisher.invalidate(owner);
        this.eventPublisher.publish("profile.deleted", Map.of(
                "owner", owner
        ));
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
        boolean cacheHit = this.profileCache.get(owner).isPresent();
        var entity = cacheHit ? this.profileCache.get(owner).orElse(null) : this.repository.findById(owner).orElse(null);
        if (entity == null) {
            return HttpResponse.notFound(new PunishProfileResponse.ErrorResponse("There is no profile linked to the given owner"));
        }
        if (!cacheHit) {
            this.profileCache.put(owner, entity);
        }
        PunishmentEntity activeBan = entity.getActiveBan();
        PunishmentEntity activeChatBan = entity.getActiveChatBan();
        boolean expired = false;
        if (activeBan != null && PunishmentExpiry.isExpired(activeBan)) {
            entity.setActiveBan(null);
            entity.getHistory().add(activeBan);
            publishExpired(owner, activeBan);
            expired = true;
        }
        if (activeChatBan != null && PunishmentExpiry.isExpired(activeChatBan)) {
            entity.setActiveChatBan(null);
            entity.getHistory().add(activeChatBan);
            publishExpired(owner, activeChatBan);
            expired = true;
        }
        if (expired) {
            entity = this.repository.update(entity);
            this.profileCache.put(owner, entity);
        }
        return HttpResponse.ok(entity.toDTO());
    }

    private void publishExpired(String owner, PunishmentEntity expired) {
        this.eventPublisher.publish("punishment.expired", Map.of(
                "owner", owner,
                "punishmentIdentifier", expired.getIdentifier()
        ));
    }

    /**
     * Records session telemetry (Phase 7's dashboard enrichment: Java/Bedrock protocol version
     * and client brand) into the profile's existing metaData, so the already-existing profile
     * listing/get endpoints surface it without a separate dashboard endpoint. Fields are merged,
     * not overwritten - {@code protocolVersion} arrives at login, {@code clientBrand} arrives
     * slightly later as a separate plugin-channel negotiation, so a single call rarely has both.
     * Not cache-invalidated deliberately - this is telemetry, not a punishment action, so a
     * brief staleness window is an acceptable tradeoff against extra RabbitMQ chatter on every
     * single login.
     */
    @Operation(
            description = "Records session telemetry (protocol version / client brand) for the dashboard",
            operationId = "updateSessionInfo",
            tags = {"PunishProfile"}
    )
    @ApiResponse(responseCode = "200", description = "Session info recorded")
    @Validated
    @Post("/{owner}/session")
    public HttpResponse<PunishProfileResponse> updateSessionInfo(
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
            @Body @Valid SessionInfoDTO info
    ) {
        PunishmentProfileEntity profile = this.repository.findById(owner).orElse(null);
        boolean isNew = profile == null;
        if (isNew) {
            profile = new PunishmentProfileEntity(owner, null, null, new ArrayList<>(), new HashMap<>());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> sessionInfo = (Map<String, Object>) profile.getMetaData()
                .computeIfAbsent("sessionInfo", key -> new HashMap<String, Object>());
        if (info.protocolVersion() != null) {
            sessionInfo.put("protocolVersion", info.protocolVersion());
        }
        if (info.clientBrand() != null) {
            sessionInfo.put("clientBrand", info.clientBrand());
        }
        sessionInfo.put("lastSeenAt", System.currentTimeMillis());

        PunishmentProfileEntity saved = isNew ? this.repository.save(profile) : this.repository.update(profile);
        return HttpResponse.ok(saved.toDTO());
    }
}
