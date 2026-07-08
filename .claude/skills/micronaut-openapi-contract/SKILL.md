---
name: micronaut-openapi-contract
description: OpenAPI/Swagger documentation-layer rule for Micronaut REST endpoints, in any Micronaut project using swagger-annotations - not tied to one specific codebase. @Operation/@ApiResponse/@Schema/@ArraySchema annotations live on a dedicated *Api interface that the controller (micronaut-controller-layer) implements - never directly on the controller implementation class. Micronaut/Swagger reads @Operation annotations off an implemented interface's methods, so this is a supported pattern in any Micronaut project, not a hypothetical one. Trigger whenever adding, changing, or reviewing these annotations for an endpoint, or whenever a controller class is bloated with Swagger annotation blocks.
---

# Micronaut OpenAPI contract layer

## Where this sits in the layering

```
Controller (micronaut-controller-layer) implements *Api (this skill) - documentation only, no logic
```

This is a documentation-only layer that exists purely so `@Operation`/`@ApiResponse` metadata
doesn't have to live inline on the controller implementation - a habit that can balloon a
controller to 30-40 lines of annotations per endpoint. It applies to any Micronaut backend using
swagger-annotations, not one specific codebase.

## The rule

- **One `*Api` interface per controller**, named after the resource (`WidgetApi` for
  `WidgetController`), living alongside the controller.
- **The interface declares the method signatures and carries every Swagger annotation**:
  `@Operation` (summary, description, operationId, tags), `@ApiResponse` (one per status code),
  `@Schema`/`@ArraySchema` for response bodies. Request-side validation annotations
  (`@Valid @Body`) stay on the interface method parameters too, since they're part of the method
  signature the controller overrides.
- **The controller `implements` the interface** and puts none of the above on its own
  overriding methods - only `@Override` plus whatever routing/DI annotations the project needs
  resolved at the implementation (`@Controller`, versioning/security annotations if any,
  `@Inject` stay on the implementation class, since those are runtime/DI concerns, not
  documentation).
- **Don't duplicate the annotations on both interface and implementation.** If Swagger stops
  picking up the docs after this split for some Micronaut-version-specific reason, that's a
  signal to investigate the actual cause (annotation retention/inheritance config) rather than
  duplicating annotations as a workaround - this pattern is confirmed supported across Micronaut
  projects, so a real repro should be tracked down instead of silently reverting to inline
  annotations.

## Review checklist

- [ ] Does the controller implementation class have any `@Operation`/`@ApiResponse` on its own
      methods? -> move to a `*Api` interface.
- [ ] Does the `*Api` interface exist and does the controller `implements` it?
- [ ] Is every status code the endpoint can actually return (success + each error path the
      service layer's outcome type can produce) documented with an `@ApiResponse`?

## Example (any resource)

```java
public interface WidgetApi {

    @Operation(summary = "Create widget", operationId = "createWidget", tags = {"Widget"})
    @ApiResponse(
            responseCode = "200",
            description = "Widget created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WidgetDTO.class))
    )
    @ApiResponse(responseCode = "405", description = "Identifier must be null for creation")
    @Post("/")
    HttpResponse<WidgetDTO> create(@Valid @Body WidgetRequestDTO request);

    @Operation(summary = "Get widget by ID", operationId = "getWidgetById", tags = {"Widget"})
    @ApiResponse(
            responseCode = "200",
            description = "Widget found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WidgetDTO.class))
    )
    @ApiResponse(responseCode = "404", description = "Widget not found")
    @Get("/{identifier}")
    HttpResponse<WidgetDTO> findById(UUID identifier);
}
```

```java
@Controller("/widget")
public class WidgetController implements WidgetApi {
    // @Override methods carry no Swagger annotations - see micronaut-controller-layer
}
```

## Observed in real Micronaut codebases

- **Blackhole** (`OneLiteFeatherNET/Blackhole`) - every controller currently carries its
  `@Operation`/`@ApiResponse` annotations inline; the complete `PunishmentTemplateApi` interface
  (all five endpoints, extracted from `PunishmentTemplateHandler`) is worked out at
  `.claude/skills/micronaut-service-layer/references/punishment-template-refactor-example.md`.
- **Otis** (`OneLiteFeatherNET/Otis`) - `OtisRequestsController` carries `@Operation` plus
  one-to-three `@ApiResponse` blocks on all six of its methods, and `OtisSearchController` does
  the same on two more - all inline, with no `*Api` interface in sight.

Same annotation-bulk habit in two unrelated Micronaut codebases from the same org - evidence this
is worth designing against by default, not a one-off in a single project.

## See also

- `micronaut-controller-layer` - the class that implements this interface.
- `micronaut-service-layer` - where the business logic behind these endpoints actually lives.
- `micronaut-dto-contract` - the DTOs referenced in `@Schema(implementation = ...)`.
- `micronaut-error-response-contract` - each non-200 `@ApiResponse` here should reference a
  defined error DTO's schema, never document a status code with no body.
