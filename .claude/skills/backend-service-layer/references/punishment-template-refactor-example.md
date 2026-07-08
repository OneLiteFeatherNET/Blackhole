This is a worked, non-production sketch of applying the `backend-controller-layer`,
`backend-service-layer`, and `backend-openapi-contract` skills together to
`PunishmentTemplateHandler.java`. It is here as a reference for shape/idiom, not something to
apply automatically - if the team decides to actually do this refactor, wire it up as a real PR
(imports, package layout under `punishmenttemplate/` or wherever the team wants it, and check it
still compiles) rather than pasting this verbatim.

## 1. `controller/PunishmentTemplateApi.java` - the OpenAPI contract

```java
package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.dto.PunishTemplateDTO;
import net.onelitefeather.blackhole.backend.dto.PunishTemplateRequestDTO;

import java.util.UUID;

/**
 * OpenAPI contract for punishment-template CRUD, kept off {@link PunishmentTemplateController} so
 * the controller stays a lean HTTP adapter. Micronaut/Swagger reads @Operation/@ApiResponse off
 * the interface method an implementation overrides.
 */
public interface PunishmentTemplateApi {

    @Operation(
            summary = "Create punishment template",
            description = "Creates a new punishment template.",
            operationId = "addTemplate",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Template successfully created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.class))
    )
    @ApiResponse(responseCode = "405", description = "Method not allowed - identifier must be null for creation")
    @ApiResponse(responseCode = "400", description = "Invalid template data")
    @Post("/")
    HttpResponse<PunishTemplateDTO> addTemplate(@Valid @Body PunishTemplateRequestDTO template);

    @Operation(
            summary = "Update punishment template",
            description = "Updates an existing punishment template.",
            operationId = "updateTemplate",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Template successfully updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.class))
    )
    @ApiResponse(responseCode = "404", description = "Template not found")
    @ApiResponse(responseCode = "400", description = "Invalid template data")
    @Post("/update")
    HttpResponse<PunishTemplateDTO> updateTemplate(@Valid @Body PunishTemplateRequestDTO template);

    @Operation(
            summary = "Delete punishment template",
            description = "Deletes a punishment template by its identifier",
            operationId = "deleteTemplate",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Template successfully deleted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.class))
    )
    @ApiResponse(responseCode = "404", description = "Template not found")
    @Delete("/delete/{identifier}")
    HttpResponse<PunishTemplateDTO> removeTemplate(@PathVariable UUID identifier);

    @Operation(
            summary = "Get all punishment templates",
            description = "Retrieves a paginated list of all punishment templates in the system",
            operationId = "getAllTemplates",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved punishment templates",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(
                            schema = @Schema(implementation = PunishTemplateDTO.class),
                            arraySchema = @Schema(implementation = Page.class)
                    )
            )
    )
    @Get("/")
    HttpResponse<Page<PunishTemplateDTO>> getAll(Pageable pageable);

    @Operation(
            summary = "Get punishment template by ID",
            description = "Retrieves a specific punishment template by its identifier",
            operationId = "getTemplateById",
            tags = {"Punishment Templates"}
    )
    @ApiResponse(
            responseCode = "200",
            description = "Template successfully retrieved",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PunishTemplateDTO.class))
    )
    @ApiResponse(responseCode = "404", description = "Template not found")
    @Get("/{identifier}")
    HttpResponse<PunishTemplateDTO> get(UUID identifier);
}
```

## 2. `template/PunishmentTemplateOutcome.java` - service result types

Same idiom as `appeal/DecisionOutcome.java` and `appeal/EligibilityResult.java`: small
records/sealed types the service returns, that the controller maps 1:1 to an `HttpResponse`,
instead of the service throwing generic exceptions or the controller re-deriving what happened.

```java
package net.onelitefeather.blackhole.backend.template;

import net.onelitefeather.blackhole.backend.dto.PunishTemplateDTO;

public sealed interface CreateOutcome {
    record Created(PunishTemplateDTO template) implements CreateOutcome {}
    record IdentifierNotAllowed() implements CreateOutcome {}
}
```

```java
package net.onelitefeather.blackhole.backend.template;

import net.onelitefeather.blackhole.backend.dto.PunishTemplateDTO;

public sealed interface UpdateOutcome {
    record Updated(PunishTemplateDTO template) implements UpdateOutcome {}
    record MissingIdentifier() implements UpdateOutcome {}
    record NotFound() implements UpdateOutcome {}
}
```

## 3. `template/PunishmentTemplateService.java` - the business logic

```java
package net.onelitefeather.blackhole.backend.template;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.dto.PunishTemplateDTO;
import net.onelitefeather.blackhole.backend.dto.PunishTemplateRequestDTO;

import java.util.Optional;
import java.util.UUID;

@Singleton
public class PunishmentTemplateService {

    private final PunishmentTemplateRepository templateRepository;

    public PunishmentTemplateService(PunishmentTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    public CreateOutcome create(PunishTemplateRequestDTO template) {
        if (template.identifier() != null) {
            return new CreateOutcome.IdentifierNotAllowed();
        }
        PunishmentTemplateEntity saved = this.templateRepository.save(PunishmentTemplateEntity.toEntity(template));
        return new CreateOutcome.Created(saved.toDTO());
    }

    public UpdateOutcome update(PunishTemplateRequestDTO template) {
        if (template.identifier() == null) {
            return new UpdateOutcome.MissingIdentifier();
        }
        if (this.templateRepository.findById(template.identifier()).isEmpty()) {
            return new UpdateOutcome.NotFound();
        }
        PunishmentTemplateEntity saved = this.templateRepository.update(PunishmentTemplateEntity.toEntity(template));
        return new UpdateOutcome.Updated(saved.toDTO());
    }

    public Optional<PunishTemplateDTO> remove(UUID identifier) {
        return this.templateRepository.findById(identifier).map(entity -> {
            this.templateRepository.delete(entity);
            return entity.toDTO();
        });
    }

    public Page<PunishTemplateDTO> findAll(Pageable pageable) {
        return this.templateRepository.findAll(pageable).map(PunishmentTemplateEntity::toDTO);
    }

    public Optional<PunishTemplateDTO> find(UUID identifier) {
        return this.templateRepository.findById(identifier).map(PunishmentTemplateEntity::toDTO);
    }
}
```

## 4. `controller/PunishmentTemplateController.java` - the thin adapter

```java
package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.core.version.annotation.Version;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.dto.PunishTemplateDTO;
import net.onelitefeather.blackhole.backend.dto.PunishTemplateRequestDTO;
import net.onelitefeather.blackhole.backend.template.CreateOutcome;
import net.onelitefeather.blackhole.backend.template.PunishmentTemplateService;
import net.onelitefeather.blackhole.backend.template.UpdateOutcome;

import java.util.UUID;

@Version(ApiVersion.V1)
@Controller("/template")
public class PunishmentTemplateController implements PunishmentTemplateApi {

    private final PunishmentTemplateService templateService;

    @Inject
    public PunishmentTemplateController(PunishmentTemplateService templateService) {
        this.templateService = templateService;
    }

    @Override
    public HttpResponse<PunishTemplateDTO> addTemplate(@Valid @Body PunishTemplateRequestDTO template) {
        return switch (this.templateService.create(template)) {
            case CreateOutcome.Created c -> HttpResponse.ok(c.template());
            case CreateOutcome.IdentifierNotAllowed ignored -> HttpResponse.notAllowed();
        };
    }

    @Override
    public HttpResponse<PunishTemplateDTO> updateTemplate(@Valid @Body PunishTemplateRequestDTO template) {
        return switch (this.templateService.update(template)) {
            case UpdateOutcome.Updated u -> HttpResponse.ok(u.template());
            case UpdateOutcome.MissingIdentifier ignored -> HttpResponse.badRequest();
            case UpdateOutcome.NotFound ignored -> HttpResponse.notFound();
        };
    }

    @Override
    public HttpResponse<PunishTemplateDTO> removeTemplate(@PathVariable UUID identifier) {
        return this.templateService.remove(identifier)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    @Override
    public HttpResponse<Page<PunishTemplateDTO>> getAll(Pageable pageable) {
        return HttpResponse.ok(this.templateService.findAll(pageable));
    }

    @Override
    public HttpResponse<PunishTemplateDTO> get(UUID identifier) {
        return this.templateService.find(identifier)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }
}
```

Every method is now: call the service, map its outcome to an `HttpResponse`. No repository
import, no inline validation, no Swagger annotations - all of that moved to
`PunishmentTemplateService` and `PunishmentTemplateApi` respectively.
