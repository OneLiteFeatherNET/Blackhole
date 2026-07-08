---
name: backend-service-layer
description: Application/business-logic layer rule for the Blackhole backend (Micronaut, layered as controller -> service -> repository). Use whenever adding business logic, validation rules, existence checks, or persistence orchestration for a backend feature, whenever a controller (backend-controller-layer) needs somewhere to delegate to, or whenever you notice a controller injecting a *Repository directly. Every feature's logic belongs in a @Singleton service colocated in its own feature package (e.g. elo/, appeal/, punishment/, imports/ - never a generic service/ package) that owns the *Repository dependency and returns small outcome records/enums the controller maps 1:1 to an HttpResponse. Trigger proactively for "where should this logic go", "add a service", "this controller has too much logic in it", or when reviewing where a business rule/validation check should live.
---

# Backend service layer (Blackhole)

## Where this sits in the layering

```
Controller (backend-controller-layer) -> Service (this skill) -> Repository/Entity
```

The service is where persistence meets business rules. It is the only layer allowed to depend on
a `*Repository`.

## The rule

- **One `@Singleton` service per feature, colocated in that feature's own package.** Follow the
  precedent already in this codebase: `elo/EloService.java`, `elo/ChatToxicityService.java`,
  `appeal/AppealEligibilityService.java`, `appeal/AppealDecisionService.java`,
  `imports/VanillaImportService.java`, `punishment/PunishmentApplicationService.java`. Don't
  create a generic top-level `service/` package - the service lives next to the domain concept it
  serves.
- **The service owns the `*Repository` dependency**, all transactional/business logic
  (validation, existence checks, create-vs-update branching, entity<->DTO mapping), and nothing
  about HTTP (no `HttpResponse`, no `@Secured`, no routing annotations - those are the
  controller's job).
- **Return small outcome types, don't just return an entity/DTO or throw a generic exception.**
  Mirror the existing idiom: `appeal/DecisionOutcome.java` is a plain enum, `appeal/
  EligibilityResult.java` is a record. For an operation with more than one distinct result shape
  (created vs. rejected, updated vs. not-found vs. invalid), a small sealed interface of records
  works well - the controller then does a `switch` that maps 1:1 to an `HttpResponse`, with no
  business meaning re-derived on the controller side.
- **Configuration the service needs (thresholds, day counts, feature flags) comes in via
  `@Value("${blackhole....}")` constructor parameters**, same as `AppealEligibilityService`,
  consistent with this repo's env-var-driven config convention.

## Review checklist

- [ ] Is the service a `@Singleton` in a feature package, not a generic `service/` folder?
- [ ] Does it own the `*Repository`, with no controller reaching around it to the repository
      directly?
- [ ] Does every public method return something the caller can act on without re-deriving
      "what happened" (an outcome record/enum), rather than a bare entity/DTO plus thrown
      exceptions for every edge case?
- [ ] Is there zero `io.micronaut.http.*` import in the service (no `HttpResponse`, no routing
      annotations)? If there is, that logic belongs back in the controller's response-mapping
      step, not here.

## Example

The full four-file worked example - `PunishmentTemplateApi` (interface),
`PunishmentTemplateService` (this layer), `PunishmentTemplateController` (the caller), and the
`CreateOutcome`/`UpdateOutcome` records - lives at
`references/punishment-template-refactor-example.md` in this skill. It refactors
`PunishmentTemplateHandler.java`, which today injects `PunishmentTemplateRepository` directly and
does its create/update/existence branching inline - exactly what this rule moves into a service.

## Known non-conforming controllers today (informational, not a mandate to bulk-refactor)

`PunishmentTemplateHandler`, `PunishmentProfileHandler`, and parts of `AppealController`,
`ConnectorAuthController`, `EloController`, `PunishmentEntityController`, `ReportController`
still inject a repository straight into the controller with no service in between. Add the
missing service when you're already touching one of these for another reason - this isn't a
mandate to refactor them all in one pass.

## See also

- `backend-controller-layer` - the thin adapter that calls into this layer.
- `backend-openapi-contract` - documentation lives separately, not in the service.
- `backend-dto-contract` - the request/response DTOs a service method takes and produces.
