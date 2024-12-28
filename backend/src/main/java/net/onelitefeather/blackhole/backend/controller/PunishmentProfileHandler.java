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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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
    public PunishmentProfileHandler(PunishmentProfileRepository repository) {
        this.repository = repository;
    }

    /**
     * Add a new punishment profile.
     *
     * @param profileEntity the profile to add
     * @return the added profile
     */
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
    @Validated
    @Post(value = "/update/{owner}")
    public HttpResponse<PunishProfileResponse> update(@Valid @Body PunishProfileDTO profileEntity,@Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner) {
        if (!profileEntity.owner().equals(owner)) {
            return HttpResponse.badRequest();
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
    @Validated
    @Delete(value = "/delete/{owner}")
    public HttpResponse<PunishProfileResponse> delete(@Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner) {
        PunishmentProfileEntity entity = this.repository.findById(owner).orElse(null);
        if (entity == null) {
            return HttpResponse.notFound();
        }
        this.repository.delete(entity);
        return HttpResponse.ok(entity.toDTO());
    }

    /**
     * Get all punishment profiles.
     *
     * @return a list of all punishment profiles
     */
    @Get("/all")
    public HttpResponse<Page<PunishProfileResponse>> getAll(Pageable pageable) {
        Page<PunishmentProfileEntity> entities = this.repository.findAll(pageable);
        return HttpResponse.ok(entities.map(PunishmentProfileEntity::toDTO));
    }

    /**
     * Get a punishment profile by the owner.
     *
     * @return a one punishment profile by the owner
     */
    @Get("/{owner}")
    public HttpResponse<PunishProfileResponse> getById(@Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner) {
        var entity = this.repository.findById(owner).map(PunishmentProfileEntity::toDTO).orElse(null);
        if (entity == null) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(entity);
    }
}
