---
name: backend-controller-layer
description: Presentation-layer rule for Micronaut REST controllers in backend/src/main/java/net/onelitefeather/blackhole/backend/controller/ (Blackhole backend, layered as controller -> service -> repository). Use whenever creating a new controller, adding or changing an endpoint, or reviewing an existing controller - a controller must stay a thin HTTP adapter: routing + API versioning (@Version) + exactly one service call per method + mapping that service's result to an HttpResponse. It must never inject a *Repository, contain business/validation branching, or carry @Operation/@ApiResponse annotations directly (those belong to backend-service-layer and backend-openapi-contract respectively). Trigger proactively for "add an endpoint", "create a controller", "review this controller", or "where should this check live" in this repo, even if the user doesn't name the pattern.
---

# Backend controller layer (Blackhole)

## Where this sits in the layering

```
HTTP request
   -> Controller        (this skill)      - routing, API versioning, one delegate call, response mapping
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
  `backend-openapi-contract`. The controller class itself carries `@Controller`, `@Version`, and
  `@Inject` only - this codebase has no per-endpoint auth annotation to worry about (the JWT/
  `@Secured` system was removed entirely; every endpoint is open and trust is established at the
  network boundary instead).
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
@Version(ApiVersion.V1)
@Controller("/template")
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
    // ...
}
```

No repository import, no `@Operation`/`@ApiResponse`, one line per method. The full four-file
worked example (API interface, controller, service, outcome records) lives at
`.claude/skills/backend-service-layer/references/punishment-template-refactor-example.md`.

## Known non-conforming controllers today (informational, not a mandate to bulk-refactor)

`PunishmentTemplateHandler`, `PunishmentProfileHandler`, and parts of `AppealController`,
`ConnectorController`, `EloController`, `PunishmentEntityController`, `ReportController`
still inject a repository and/or branch on business rules directly. Apply this rule to new
controllers, and to these when you're already touching them for another reason.

## This pattern repeats beyond Blackhole (Otis)

The sibling project `OneLiteFeatherNET/Otis` (also a Micronaut backend) shows the same shape in
`OtisRequestsController`/`OtisSearchController`: both inject `OtisPlayerRepository` straight into
the controller, `update()` inlines a business check (`playerDTO.playerUuid().equals(owner)` ->
400) plus an existence check (-> 404) before touching the repository, and there is no `*Api`
interface anywhere - the `@Operation`/`@ApiResponse` blocks sit directly on six controller
methods. That's the same three violations this rule targets, in an unrelated codebase from the
same org - good evidence this is a recurring shape to design against, not a one-off in
`PunishmentTemplateHandler.java`.

## Generic example (any resource)

Substitute `Widget` for whatever resource you're actually building - the shape doesn't change:

```java
@Version(ApiVersion.V1)
@Controller("/widget")
public class WidgetController implements WidgetApi {

    private final WidgetService widgetService;

    @Inject
    public WidgetController(WidgetService widgetService) {
        this.widgetService = widgetService;
    }

    @Override
    public HttpResponse<WidgetDTO> create(@Valid @Body WidgetRequestDTO request) {
        return switch (this.widgetService.create(request)) {
            case CreateOutcome.Created c -> HttpResponse.ok(c.widget());
            case CreateOutcome.IdentifierNotAllowed ignored -> HttpResponse.notAllowed();
        };
    }

    @Override
    public HttpResponse<WidgetDTO> findById(UUID identifier) {
        return this.widgetService.find(identifier)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }
}
```

No `Widget*Repository` import, no branching beyond the `switch`/`.map(...).orElseGet(...)` that
turns the service's answer into a response - that's the whole pattern, independent of domain.

## See also

- `backend-service-layer` - where the business logic and repository dependency actually go.
- `backend-openapi-contract` - where the Swagger annotations actually go.
- `backend-dto-contract` - the request/response DTO shape a controller method deals with.
