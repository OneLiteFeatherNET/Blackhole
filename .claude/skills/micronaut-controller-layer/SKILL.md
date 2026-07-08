---
name: micronaut-controller-layer
description: Presentation-layer rule for Micronaut REST controllers, in any Micronaut backend that separates controller/service/repository layers - not tied to one specific project. Use whenever creating a new controller, adding or changing an endpoint, or reviewing an existing controller - a controller must stay a thin HTTP adapter: routing/versioning annotations + exactly one service call per method + mapping that service's result to an HttpResponse. It must never inject a repository directly, contain business/validation branching, or carry @Operation/@ApiResponse annotations directly (those belong to micronaut-service-layer and micronaut-openapi-contract respectively). Trigger proactively for "add an endpoint", "create a controller", "review this controller", or "where should this check live" in any Micronaut codebase, even if the user doesn't name the pattern.
---

# Micronaut controller layer

## Where this sits in the layering

```
HTTP request
   -> Controller        (this skill)                 - routing, versioning, one delegate call, response mapping
   -> *Api interface     (micronaut-openapi-contract) - carries the OpenAPI/Swagger documentation
   -> *Service           (micronaut-service-layer)    - business logic, validation, persistence orchestration
   -> *Repository/Entity (Micronaut Data)
```

Request/response shape at the boundary is covered separately by `micronaut-dto-contract`. This
skill only governs the controller class itself, and applies to any Micronaut REST backend that
separates these layers - not to one specific codebase.

## The rule

A controller method does exactly three things: read the already-deserialized request DTO, call
**one** method on a service, and translate whatever that service returns (or throws) into an
`HttpResponse`. Nothing else belongs there:

- **No repository injection.** A controller constructor should never take a Micronaut Data
  repository parameter, and the class should have zero imports from a `database.repository` (or
  equivalent) package. If you find yourself wanting one, that dependency belongs in a service
  (see `micronaut-service-layer`).
- **No business/validation branching.** Deciding create-vs-update from a nullable identifier,
  checking whether an entity exists before acting on it, enforcing a business rule before saving
  - all of that is domain logic, not routing. It belongs in the service; the controller just maps
  the service's outcome (e.g. `Created` / `NotFound` / `IdentifierNotAllowed`) to the matching
  `HttpResponse`. A controller method with more than one `if`/`switch` branch is a sign logic
  leaked in from the service.
- **No OpenAPI annotations on the implementation.** `@Operation`, `@ApiResponse`, `@Schema`,
  `@ArraySchema` live on a dedicated `*Api` interface that the controller `implements` - see
  `micronaut-openapi-contract`. The controller class itself carries only routing/DI concerns:
  `@Controller`, whatever versioning scheme the project uses (e.g. Micronaut's `@Version`, or a
  URI-prefix convention), and `@Inject`. If the project also gates endpoints with a per-request
  security annotation (Micronaut Security's `@Secured` or an equivalent), that lives here too -
  it's a routing/DI concern, not business logic. Some Micronaut backends have no such annotation
  at all (trust established at the network boundary instead); in that case there's simply
  nothing extra to add.
- **Name it `*Controller`**, not `*Handler` or another suffix - the conventional Micronaut naming,
  and it keeps controllers grep-able as a group across a codebase.

## Review checklist

- [ ] Does the constructor inject a repository? -> move it behind a `*Service`.
- [ ] Does any method body branch on anything other than "which `HttpResponse` to build from the
      service's result"? -> move that branch into the service.
- [ ] Are `@Operation`/`@ApiResponse` annotations sitting on this class's methods? -> extract a
      `*Api` interface (`micronaut-openapi-contract`).
- [ ] Does a new controller class end in `Controller`?

## Before / after (generic example: any resource)

Substitute `Widget` for whatever resource you're actually building - the shape doesn't change:

**Before** (the smell to look for):

```java
@Controller("/widget")
public class WidgetHandler {

    private final WidgetRepository widgetRepository; // repository injected straight into the controller

    @Operation(summary = "Create widget", /* ...20+ more lines of Swagger annotations... */)
    @Post("/")
    public HttpResponse<WidgetDTO> create(@Valid @Body WidgetRequestDTO request) {
        if (request.identifier() != null) {           // business rule inline in the controller
            return HttpResponse.notAllowed();
        }
        WidgetEntity saved = this.widgetRepository.save(WidgetEntity.toEntity(request));
        return HttpResponse.ok(saved.toDTO());
    }
}
```

**After:**

```java
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

No repository import, no `@Operation`/`@ApiResponse`, no branching beyond the
`switch`/`.map(...).orElseGet(...)` that turns the service's answer into a response - that's the
whole pattern, independent of domain or project. The bodyless `HttpResponse.notAllowed()`/
`.notFound()` calls above are simplified for this example; see
`micronaut-error-response-contract` for why a real error path should carry a DTO body instead of
an empty response.

## Observed in real Micronaut codebases

This isn't a hypothetical smell - it shows up in real, unrelated Micronaut projects:

- **Blackhole** (`OneLiteFeatherNET/Blackhole`) - `PunishmentTemplateHandler`,
  `PunishmentProfileHandler`, and parts of `AppealController`, `ConnectorController`,
  `EloController`, `PunishmentEntityController`, `ReportController` inject a repository and/or
  branch on business rules directly. A full worked refactor of `PunishmentTemplateHandler` (API
  interface, controller, service, outcome records) is at
  `.claude/skills/micronaut-service-layer/references/punishment-template-refactor-example.md`.
- **Otis** (`OneLiteFeatherNET/Otis`) - `OtisRequestsController`/`OtisSearchController` inject
  `OtisPlayerRepository` straight into the controller, inline a business check
  (`playerDTO.playerUuid().equals(owner)` -> 400) plus an existence check (-> 404), and carry
  `@Operation`/`@ApiResponse` directly on six controller methods with no `*Api` interface at all.

Two unrelated codebases from the same org independently arrived at the same anti-pattern - good
evidence this is worth designing against by default, not something to fix only when you happen
to notice it.

## See also

- `micronaut-service-layer` - where the business logic and repository dependency actually go.
- `micronaut-openapi-contract` - where the Swagger annotations actually go.
- `micronaut-dto-contract` - the request/response DTO shape a controller method deals with.
- `micronaut-error-response-contract` - how the controller should turn a service's error result
  into an `HttpResponse` that still carries a body, never an empty error response.
