---
name: micronaut-service-layer
description: Application/business-logic layer rule for Micronaut backends, in any Micronaut project - not tied to one specific codebase. Business/persistence logic belongs in a @Singleton service colocated in its own feature package (never a generic top-level service/ package), owning the repository dependency and returning small outcome records/enums the controller maps 1:1 to an HttpResponse. Use whenever adding business logic, validation rules, existence checks, or persistence orchestration for a feature; whenever a controller (micronaut-controller-layer) needs somewhere to delegate to; or whenever a controller is found injecting a repository directly. Trigger proactively for "where should this logic go", "add a service", "this controller has too much logic in it", or reviewing where a business rule/validation check should live, in any Micronaut codebase.
---

# Micronaut service layer

## Where this sits in the layering

```
Controller (micronaut-controller-layer) -> Service (this skill) -> Repository/Entity
```

The service is where persistence meets business rules. It is the only layer allowed to depend on
a repository, and the rule applies to any Micronaut backend that separates these layers - not to
one specific codebase.

## The rule

- **One `@Singleton` service per feature, colocated in that feature's own package** (e.g.
  `order/OrderService.java`, `payment/PaymentService.java`) - not a generic top-level `service/`
  package. The service lives next to the domain concept it serves, the same way a repository
  lives next to its entity.
- **The service owns the repository dependency**, all transactional/business logic (validation,
  existence checks, create-vs-update branching, entity<->DTO mapping), and nothing about HTTP (no
  `HttpResponse`, no routing/versioning annotations - those are the controller's job).
- **Return small outcome types, don't just return an entity/DTO or throw a generic exception.** A
  plain enum works for a single yes/no result; for an operation with more than one distinct
  result shape (created vs. rejected, updated vs. not-found vs. invalid), a small sealed
  interface of records works well - the controller then does a `switch` that maps 1:1 to an
  `HttpResponse`, with no business meaning re-derived on the controller side. If that outcome is
  also what gets serialized as the response body (not just an internal signal the controller
  discards after mapping), see `micronaut-error-response-contract` for the specific
  sealed-DTO-plus-`ErrorResponse` shape - the API must always answer with a defined DTO, never
  let an exception cross the controller/service boundary for an expected failure.
- **Configuration the service needs (thresholds, feature flags, day counts) comes in via
  constructor-injected config values** (Micronaut's `@Value("${my.app.thing:default}")`),
  consistent with an env-var-driven config convention rather than hardcoded constants.

## Review checklist

- [ ] Is the service a `@Singleton` in a feature package, not a generic `service/` folder?
- [ ] Does it own the repository, with no controller reaching around it to the repository
      directly?
- [ ] Does every public method return something the caller can act on without re-deriving
      "what happened" (an outcome record/enum), rather than a bare entity/DTO plus thrown
      exceptions for every edge case?
- [ ] Is there zero `io.micronaut.http.*` import in the service (no `HttpResponse`, no routing
      annotations)? If there is, that logic belongs back in the controller's response-mapping
      step, not here.

## Example (any resource)

```java
package net.example.app.widget;

@Singleton
public class WidgetService {

    private final WidgetRepository widgetRepository;

    public WidgetService(WidgetRepository widgetRepository) {
        this.widgetRepository = widgetRepository;
    }

    public CreateOutcome create(WidgetRequestDTO request) {
        if (request.identifier() != null) {
            return new CreateOutcome.IdentifierNotAllowed();
        }
        WidgetEntity saved = this.widgetRepository.save(WidgetEntity.toEntity(request));
        return new CreateOutcome.Created(saved.toDTO());
    }

    public Optional<WidgetDTO> find(UUID identifier) {
        return this.widgetRepository.findById(identifier).map(WidgetEntity::toDTO);
    }
}
```

If a service you're writing doesn't fit this exact shape because it needs to orchestrate across
multiple repositories, that's still fine - the rule is "the repository dependency and the
branching logic live here", not "exactly one repository per service".

## Observed in real Micronaut codebases

- **Blackhole** (`OneLiteFeatherNET/Blackhole`) already does this right in several feature
  packages - `elo/EloService.java`, `elo/ChatToxicityService.java`,
  `appeal/AppealEligibilityService.java`, `appeal/AppealDecisionService.java`,
  `imports/VanillaImportService.java`, `punishment/PunishmentApplicationService.java` - while
  `PunishmentTemplateHandler`, `PunishmentProfileHandler`, and parts of `AppealController`,
  `ConnectorController`, `EloController`, `PunishmentEntityController`, `ReportController` still
  inject a repository straight into the controller with no service in between. The full
  four-file worked example (`PunishmentTemplateApi`, `PunishmentTemplateService`,
  `PunishmentTemplateController`, and the `CreateOutcome`/`UpdateOutcome` records) refactoring
  `PunishmentTemplateHandler` lives at `references/punishment-template-refactor-example.md` in
  this skill.
- **Otis** (`OneLiteFeatherNET/Otis`) has no service layer at all - `OtisPlayerRepository` is
  injected straight into both of its controllers, and the business rule for `update()` (the path
  `owner` must match the body's `playerUuid`, then the entity must exist) lives inline in the
  controller method rather than in something like a `player/PlayerService`.

Two unrelated Micronaut codebases from the same org show the same gap - evidence this is worth
designing against by default, not a one-off.

## See also

- `micronaut-controller-layer` - the thin adapter that calls into this layer.
- `micronaut-openapi-contract` - documentation lives separately, not in the service.
- `micronaut-dto-contract` - the request/response DTOs a service method takes and produces.
- `micronaut-error-response-contract` - when the outcome type returned here also needs to be the
  serialized HTTP response body (not just an internal signal), this is the shape to use: a sealed
  `*ResponseDTO` with a success record and error record(s) carrying an `errorMessage`.
