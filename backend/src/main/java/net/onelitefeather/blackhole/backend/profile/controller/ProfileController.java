package net.onelitefeather.blackhole.backend.profile.controller;

import net.onelitefeather.blackhole.backend.profile.ProfileAttributionGuard;
import io.micronaut.security.annotation.Secured;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.profile.ProfileCache;
import net.onelitefeather.blackhole.backend.profile.ProfileSlots;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileDTO;
import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileRequestDTO;
import net.onelitefeather.blackhole.backend.profile.dto.PunishProfileResponse;
import net.onelitefeather.blackhole.backend.profile.dto.SessionInfoDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
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
import net.onelitefeather.blackhole.backend.discord.DiscordLinkService;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.ActivePunishmentExpirer;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Version(ApiVersion.V1)
@Controller("/profile")
public class ProfileController {

    private final PunishmentProfileRepository repository;
    private final DomainEventPublisher eventPublisher;
    private final ProfileCache profileCache;
    private final ActivePunishmentExpirer expirer;
    private final ProfileSlots slots;
    private final Clock clock;
    private final ProfileAttributionGuard attributionGuard;
    private final DiscordLinkService discordLinks;

    /**
     * Create a new ProfileController.
     *
     * @param repository     the repository to use
     * @param eventPublisher publishes domain events for successful writes
     * @param profileCache   the hot-path profile-by-owner read cache, evicted on every write
     * @param expirer        rotates expired active punishments into history when a profile is read
     * @param slots          row-level profile writes (session telemetry)
     * @param clock          source of the last-seen timestamp
     * @param attributionGuard discards client-supplied {@code recordedByOid} on whole-profile writes
     * @param discordLinks   removes the player's Discord link when the profile is erased
     */
    @Inject
    public ProfileController(
            PunishmentProfileRepository repository,
            DomainEventPublisher eventPublisher,
            ProfileCache profileCache,
            ActivePunishmentExpirer expirer,
            ProfileSlots slots,
            Clock clock,
            ProfileAttributionGuard attributionGuard,
            DiscordLinkService discordLinks
    ) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.profileCache = profileCache;
        this.expirer = expirer;
        this.slots = slots;
        this.clock = clock;
        this.attributionGuard = attributionGuard;
        this.discordLinks = discordLinks;
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
    @Secured(Roles.MODERATOR)
    public HttpResponse<PunishProfileResponse> add(@Body @Valid PunishProfileRequestDTO profileEntity) {
        PunishmentProfileEntity entity = PunishmentProfileEntity.toEntity(this.attributionGuard.sanitize(profileEntity));
        PunishmentProfileEntity savedEntity = this.repository.save(entity);
        this.profileCache.evict(profileEntity.owner());
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
    @Secured(Roles.MODERATOR)
    public HttpResponse<PunishProfileResponse> update(
            @Valid @Body PunishProfileRequestDTO profileEntity,
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner
    ) {
        if (!profileEntity.owner().equals(owner)) {
            return HttpResponse.badRequest(new PunishProfileResponse.ErrorResponse("Owner in path and body do not match"));
        }
        PunishmentProfileEntity entity = PunishmentProfileEntity.toEntity(this.attributionGuard.sanitize(profileEntity));
        PunishmentProfileEntity savedEntity = this.repository.update(entity);
        this.profileCache.evict(owner);
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
    @Secured(Roles.ADMIN)
    public HttpResponse<PunishProfileResponse> delete(
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner
    ) {
        PunishmentProfileEntity entity = this.repository.findById(owner).orElse(null);
        if (entity == null) {
            return HttpResponse.badRequest(new PunishProfileResponse.ErrorResponse("There is no profile linked to the given owner"));
        }
        this.repository.delete(entity);
        this.discordLinks.removeOnErase(owner);
        this.profileCache.evict(owner);
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
    @Secured({Roles.VIEWER, Roles.PROXY})
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
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<PunishProfileResponse> getById(
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner
    ) {
        // A cached profile holding an already-expired punishment is treated as a miss, so the
        // database path below still rotates it into history and publishes punishment.expired.
        Optional<PunishProfileDTO> cached = this.profileCache.get(owner).filter(profile -> !hasExpiredPunishment(profile));
        if (cached.isPresent()) {
            return HttpResponse.ok(cached.get());
        }
        PunishmentProfileEntity entity = this.repository.findById(owner).orElse(null);
        if (entity == null) {
            return HttpResponse.notFound(new PunishProfileResponse.ErrorResponse("There is no profile linked to the given owner"));
        }
        // Rotates expired punishments into history, saves the profile and publishes punishment.expired.
        this.expirer.expire(entity);
        PunishProfileDTO dto = entity.toDTO();
        this.profileCache.put(owner, dto);
        return HttpResponse.ok(dto);
    }

    private boolean hasExpiredPunishment(PunishProfileDTO profile) {
        return profile.active().values().stream()
                .map(PunishEntryDTO::metaData)
                .anyMatch(this.expirer::isExpired);
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
    @Secured({Roles.MODERATOR, Roles.PROXY})
    public HttpResponse<PunishProfileResponse> updateSessionInfo(
            @Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
            @Body @Valid SessionInfoDTO info
    ) {
        Map<String, Object> fields = new HashMap<>();
        if (info.protocolVersion() != null) {
            fields.put("protocolVersion", info.protocolVersion());
        }
        if (info.clientBrand() != null) {
            fields.put("clientBrand", info.clientBrand());
        }
        fields.put("lastSeenAt", this.clock.millis());

        // Only the session fields are written, so a punishment applied at the same moment survives.
        this.slots.mergeSessionInfo(owner, fields);
        return HttpResponse.ok(this.repository.findById(owner).orElseThrow().toDTO());
    }
}
