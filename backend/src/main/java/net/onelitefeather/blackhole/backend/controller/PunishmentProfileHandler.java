package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import net.onelitefeather.blackhole.api.profile.PunishProfile;
import net.onelitefeather.blackhole.backend.database.models.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentProfileRepository;

import java.util.List;

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
    @Get()
    public HttpResponse<PunishProfile> add(@Body PunishProfile profileEntity) {
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
    @Post(value = "/update")
    public HttpResponse<PunishProfile> update(@Body PunishProfile profileEntity) {
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
    @Delete(value = "/delete/{owner}")
    public HttpResponse<PunishProfile> delete(String owner) {
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
    @Get("/getAll")
    public HttpResponse<List<PunishProfile>> getAll() {
        List<PunishmentProfileEntity> entities = this.repository.findAll();
        return HttpResponse.ok(entities.stream().map(PunishmentProfileEntity::toDTO).toList());
    }
}
