---
name: micronaut-service-layer
description: Application/business-logic layer rule for Micronaut backends, in any Micronaut project - not tied to one specific codebase. Business/persistence logic belongs in a @Singleton service colocated in its own feature package (never a generic top-level service/ package), owning the repository dependency and returning the resource's own sealed DTO (see micronaut-dto-contract) - success or error variant - that the controller maps 1:1 to an HttpResponse, never throwing for an expected failure. Use whenever adding business logic, validation rules, existence checks, or persistence orchestration for a feature; whenever a controller (micronaut-controller-layer) needs somewhere to delegate to; or whenever a controller is found injecting a repository directly. Trigger proactively for "where should this logic go", "add a service", "this controller has too much logic in it", or reviewing where a business rule/validation check should live, in any Micronaut codebase.
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
  existence checks, entity<->DTO mapping), and nothing about HTTP (no `HttpResponse`, no
  routing/versioning annotations - those are the controller's job). Create vs. update is no
  longer something the service branches on at runtime either - see `micronaut-dto-contract`: a
  `CreateRequest` and an `UpdateRequest` are distinct types, so the service simply has two
  methods, one per type.
- **Never throw a generic exception for an expected failure - return the resource's own DTO.**
  Per `micronaut-dto-contract`, each resource already has a sealed `WidgetDTO` with `Response`/
  `Error` variants; a service method that can fail returns that type directly
  (`new WidgetDTO.Error(...)` instead of throwing), and the controller maps it 1:1 to an
  `HttpResponse` with no business meaning re-derived on the controller side. An operation that
  genuinely cannot fail (e.g. an unconditional create) may narrow its return type to `Response`
  directly. For a purely internal signal that's never serialized back to a client, a plain
  enum/record still works fine - this rule is specifically about the outcome that becomes the
  HTTP response body.
- **Configuration the service needs (thresholds, feature flags, day counts) comes in via
  constructor-injected config values** (Micronaut's `@Value("${my.app.thing:default}")`),
  consistent with an env-var-driven config convention rather than hardcoded constants.

## Review checklist

- [ ] Is the service a `@Singleton` in a feature package, not a generic `service/` folder?
- [ ] Does it own the repository, with no controller reaching around it to the repository
      directly?
- [ ] Does every public method that can fail return the resource's own `WidgetDTO` (or a plain
      outcome type for a purely internal signal) rather than throwing for an expected failure?
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

    public WidgetDTO.Response createWidget(WidgetDTO.CreateRequest request) {
        WidgetEntity saved = this.widgetRepository.save(WidgetEntity.toEntity(request));
        return WidgetDTO.Response.createDTO(saved); // can't fail - no identifier to conflict with
    }

    public WidgetDTO updateWidget(WidgetDTO.UpdateRequest request) {
        Optional<WidgetEntity> existing = this.widgetRepository.findById(request.id());
        if (existing.isEmpty()) {
            return new WidgetDTO.Error("Widget not found");
        }
        WidgetEntity saved = this.widgetRepository.update(request.toEntity());
        return WidgetDTO.Response.createDTO(saved);
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
  `EloController`, `PunishmentEntityController`, `ReportController` still
  inject a repository straight into the controller with no service in between. The full
  four-file worked example (`PunishmentTemplateApi`, `PunishmentTemplateService`,
  `PunishmentTemplateController`, and the `CreateOutcome`/`UpdateOutcome` records) refactoring
  `PunishmentTemplateHandler` lives at `references/punishment-template-refactor-example.md` in
  this skill - it predates the unified `WidgetDTO` convention in `micronaut-dto-contract`, so
  treat it as a worked example of "service owns the repository" rather than the current DTO
  shape.
- **Otis** (`OneLiteFeatherNET/Otis`) has no service layer at all - `OtisPlayerRepository` is
  injected straight into both of its controllers, and the business rule for `update()` (the path
  `owner` must match the body's `playerUuid`, then the entity must exist) lives inline in the
  controller method rather than in something like a `player/PlayerService`.

Two unrelated Micronaut codebases from the same org show the same gap - evidence this is worth
designing against by default, not a one-off.

## See also

- `micronaut-controller-layer` - the thin adapter that calls into this layer.
- `micronaut-openapi-contract` - documentation lives separately, not in the service.
- `micronaut-dto-contract` - the sealed `WidgetDTO` shape (`CreateRequest`/`UpdateRequest`/
  `Response`/`Error`) a service method takes and produces.
