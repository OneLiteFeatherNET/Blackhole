---
name: backend-openapi-contract
description: OpenAPI/Swagger documentation-layer rule for Blackhole backend endpoints (Micronaut + swagger-annotations, layered as controller -> service -> repository). Use whenever adding, changing, or reviewing @Operation/@ApiResponse/@Schema/@ArraySchema annotations for an endpoint, or whenever a controller class is bloated with Swagger annotation blocks. The rule: these annotations live on a dedicated *Api interface (e.g. PunishmentTemplateApi) that the controller (backend-controller-layer) implements - never directly on the controller implementation class. Micronaut/Swagger reads @Operation annotations off an implemented interface's methods, so this is a supported, already-working pattern in this codebase, not a hypothetical one. Trigger whenever documenting a new endpoint, writing Swagger annotations, or reviewing a controller for how much annotation clutter it carries.
---

# Backend OpenAPI contract layer (Blackhole)

## Where this sits in the layering

```
Controller (backend-controller-layer) implements *Api (this skill) - documentation only, no logic
```

This is a documentation-only layer: it exists purely so that `@Operation`/`@ApiResponse`
metadata doesn't have to live inline on the controller implementation, which is what currently
makes `PunishmentTemplateHandler.java` balloon to ~30-40 lines of annotations per endpoint.

## The rule

- **One `*Api` interface per controller**, named after the resource (`PunishmentTemplateApi` for
  `PunishmentTemplateController`), living in the same `controller` package.
- **The interface declares the method signatures and carries every Swagger annotation**:
  `@Operation` (summary, description, operationId, tags), `@ApiResponse` (one per status code),
  `@Schema`/`@ArraySchema` for response bodies. Request-side validation annotations
  (`@Valid @Body`) stay on the interface method parameters too, since they're part of the method
  signature the controller overrides.
- **The controller `implements` the interface** and puts none of the above on its own
  overriding methods - only `@Override` plus whatever routing annotation Micronaut still needs
  resolved at the implementation (`@Controller`, `@Secured`, `@Inject` stay on the
  implementation class, since those are runtime/DI concerns, not documentation).
- **Don't duplicate the annotations on both interface and implementation.** If Swagger stops
  picking up the docs after this split for some Micronaut-version-specific reason, that's a
  signal to investigate the actual cause (annotation retention/inheritance config) rather than
  duplicating annotations as a workaround - this pattern is confirmed supported, so a real repro
  should be tracked down instead of silently reverting to inline annotations.

## Review checklist

- [ ] Does the controller implementation class have any `@Operation`/`@ApiResponse` on its own
      methods? -> move to a `*Api` interface.
- [ ] Does the `*Api` interface exist and does the controller `implements` it?
- [ ] Is every status code the endpoint can actually return (success + each error path the
      service layer's outcome type can produce) documented with an `@ApiResponse`?

## Example

```java
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
}
```

```java
@Secured({Roles.ADMIN, Roles.STAFF, Roles.SERVICE})
@Controller(value = ApiVersion.V1 + "/template")
public class PunishmentTemplateController implements PunishmentTemplateApi {
    // @Override methods carry no Swagger annotations - see backend-controller-layer
}
```

The complete `PunishmentTemplateApi` interface (all five endpoints) is in
`.claude/skills/backend-service-layer/references/punishment-template-refactor-example.md`.

## Known non-conforming controllers today (informational, not a mandate to bulk-refactor)

Every controller in this codebase currently carries its `@Operation`/`@ApiResponse` annotations
inline (there's no `*Api` interface anywhere yet). Introduce one when you're adding a new
controller or substantially reworking an existing one's endpoints - this isn't a mandate to
retrofit every controller in one pass.

## See also

- `backend-controller-layer` - the class that implements this interface.
- `backend-service-layer` - where the business logic behind these endpoints actually lives.
- `backend-dto-contract` - the DTOs referenced in `@Schema(implementation = ...)`.
