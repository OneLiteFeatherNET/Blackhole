---
name: backend-controller-layer
description: Presentation-layer rule for Micronaut REST controllers in backend/src/main/java/net/onelitefeather/blackhole/backend/controller/ (Blackhole backend, layered as controller -> service -> repository). Use whenever creating a new controller, adding or changing an endpoint, or reviewing an existing controller - a controller must stay a thin HTTP adapter: routing + @Secured + exactly one service call per method + mapping that service's result to an HttpResponse. It must never inject a *Repository, contain business/validation branching, or carry @Operation/@ApiResponse annotations directly (those belong to backend-service-layer and backend-openapi-contract respectively). Trigger proactively for "add an endpoint", "create a controller", "review this controller", or "where should this check live" in this repo, even if the user doesn't name the pattern.
---

# Backend controller layer (Blackhole)

## Where this sits in the layering

```
HTTP request
   -> Controller        (this skill)      - routing, security, one delegate call, response mapping
   -> *Api interface     (backend-openapi-contract) - carries the OpenAPI/Swagger documentation
   -> *Service           (backend-service-layer)     - business logic, validation, persistence orchestration
   -> *Repository/Entity (existing Micronaut Data convention, unchanged)
```

Request/response shape at the boundary is covered separately by `backend-dto-contract`. This
skill only governs the controller class itself.

## The rule

A controller method does exactly three things: read the already-deserialized request DTO, call
**one** method on a service, and translate whatever that service returns (or throws) into an
`HttpResponse`. Nothing else belongs there:

- **No repository injection.** A controller constructor should never take a `*Repository`
  parameter, and the class should have zero `import ...database.repository.*` lines. If you find
  yourself wanting one, that dependency belongs in a service (see `backend-service-layer`).
- **No business/validation branching.** Deciding create-vs-update from a nullable identifier,
  checking whether an entity exists before acting on it, enforcing a business rule before saving
  - all of that is domain logic, not routing. It belongs in the service; the controller just maps
  the service's outcome (e.g. `Created` / `NotFound` / `IdentifierNotAllowed`) to the matching
  `HttpResponse`. A controller method with more than one `if`/`switch` branch is a sign logic
  leaked in from the service.
- **No OpenAPI annotations on the implementation.** `@Operation`, `@ApiResponse`, `@Schema`,
  `@ArraySchema` live on a dedicated `*Api` interface that the controller `implements` - see
  `backend-openapi-contract`. The controller class itself carries `@Controller`, `@Secured`, and
  `@Inject` only.
- **Name it `*Controller`.** Every controller in this codebase follows that suffix
  (`AppealController`, `EloController`, `ReportController`, ...) except two legacy outliers
  (`PunishmentTemplateHandler`, `PunishmentProfileHandler`). Don't add new `*Handler` classes;
  renaming the existing two is optional cleanup, not required by this rule.

## Review checklist

- [ ] Does the constructor inject a `*Repository`? -> move it behind a `*Service`.
- [ ] Does any method body branch on anything other than "which `HttpResponse` to build from the
      service's result"? -> move that branch into the service.
- [ ] Are `@Operation`/`@ApiResponse` annotations sitting on this class's methods? -> extract a
      `*Api` interface (`backend-openapi-contract`).
- [ ] Does a new controller class end in `Controller`?

## Before / after

**Before** (`PunishmentTemplateHandler.java` today - abbreviated):

```java
@Controller(value = ApiVersion.V1 + "/template")
public class PunishmentTemplateHandler {

    private final PunishmentTemplateRepository templateRepository; // repository injected straight into the controller

    @Operation(summary = "Create punishment template", /* ...30 more lines of Swagger annotations... */)
    @Post("/")
    public HttpResponse<PunishTemplateDTO> addTemplate(@Valid @Body PunishTemplateRequestDTO template) {
        if (template.identifier() != null) {          // business rule inline in the controller
            return HttpResponse.notAllowed();
        }
        PunishmentTemplateEntity dbEntity = PunishmentTemplateEntity.toEntity(template);
        PunishmentTemplateEntity savedEntity = this.templateRepository.save(dbEntity);
        return HttpResponse.ok(savedEntity.toDTO());
    }
}
```

**After:**

```java
@Secured({Roles.ADMIN, Roles.STAFF, Roles.SERVICE})
@Controller(value = ApiVersion.V1 + "/template")
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
    // ...
}
```

No repository import, no `@Operation`/`@ApiResponse`, one line per method. The full four-file
worked example (API interface, controller, service, outcome records) lives at
`.claude/skills/backend-service-layer/references/punishment-template-refactor-example.md`.

## Known non-conforming controllers today (informational, not a mandate to bulk-refactor)

`PunishmentTemplateHandler`, `PunishmentProfileHandler`, and parts of `AppealController`,
`ConnectorAuthController`, `EloController`, `PunishmentEntityController`, `ReportController`
still inject a repository and/or branch on business rules directly. Apply this rule to new
controllers, and to these when you're already touching them for another reason.

## See also

- `backend-service-layer` - where the business logic and repository dependency actually go.
- `backend-openapi-contract` - where the Swagger annotations actually go.
- `backend-dto-contract` - the request/response DTO shape a controller method deals with.
