---
name: micronaut-dto-contract
description: Request/response DTO contract convention for Micronaut REST endpoints, in any Micronaut project - not tied to one specific codebase. Pair a *RequestDTO (mutation input, nullable identifier field where null means create and set means update) with a full *DTO for responses, both @Serdeable @ReflectiveAccess records with jakarta.validation constraints. Trigger whenever designing a new endpoint's request/response shape, adding a new DTO, or reviewing a DTO's shape, nullability, or validation annotations, in any Micronaut backend.
---

# Micronaut DTO contract

## Where this sits in the layering

```
Controller (micronaut-controller-layer) <-- *RequestDTO / *DTO (this skill) --> Service (micronaut-service-layer)
```

DTOs are the boundary contract: what a controller method deserializes from a request body and
what it serializes back. This convention applies to any Micronaut backend, not one specific
codebase - it's the reference shape `micronaut-controller-layer`/`micronaut-service-layer`/
`micronaut-openapi-contract` build on.

## The rule

- **A mutation endpoint gets two DTOs, not one**: a `*RequestDTO` for input, a full `*DTO` for
  output. Don't reuse the response DTO as the request body just because the fields overlap - the
  request type is what a client is allowed to send, the response type is everything the server
  hands back.
- **The nullable-`identifier` convention decides create vs. update.** On the `*RequestDTO`,
  `identifier` is `@Nullable UUID`: `null` means "create a new one", a set value means "update
  this one". Don't invent a separate `isUpdate` flag or a different DTO per operation; reuse the
  same request DTO for both create and update endpoints.
- **Both are `@Serdeable @ReflectiveAccess` records.** Records keep them immutable and
  boilerplate-free; both annotations are required for Micronaut's GraalVM-friendly
  serialization/reflection to work on these types.
- **Field-level `jakarta.validation` constraints (`@NonNull`, `@NotBlank`, etc.) belong on the
  DTO**, not re-checked imperatively in the controller or service - Micronaut validates a
  `@Valid @Body` parameter before the controller method body even runs.
- **The full response `*DTO` may implement shared marker interfaces to expose derived
  accessors** over a raw metadata map, if the project has such a shared abstraction (Blackhole's
  `phoca` module's `Metadata`/`Durationable` are one example - `creationDate()`, `duration()`,
  `translatable()`). This only applies if your project has an equivalent; otherwise a plain
  record is fine.

## Review checklist

- [ ] Does a create/update endpoint pair share one `*RequestDTO` with a nullable `identifier`,
      rather than separate Create/Update DTOs or a boolean flag?
- [ ] Is the response type a distinct, fuller `*DTO` rather than the request DTO reused as-is?
- [ ] Are both DTOs `@Serdeable @ReflectiveAccess` records with `jakarta.validation` constraints
      on the fields that need them?
- [ ] If the DTO carries a metadata map and the project has a shared metadata abstraction, does
      it implement that interface instead of hand-rolling accessors?

## Example (any resource)

```java
@Serdeable
@ReflectiveAccess
public record WidgetRequestDTO(
        @NonNull @NotBlank String name,
        @Nullable UUID identifier   // null = create, set = update
) {
}

@Serdeable
@ReflectiveAccess
public record WidgetDTO(
        @NonNull @NotBlank String name,
        UUID identifier
) {
}
```

## Observed in real Micronaut codebases

- **Blackhole** (`OneLiteFeatherNET/Blackhole`) - `PunishTemplateRequestDTO`/`PunishTemplateDTO`
  (`backend/src/main/java/net/onelitefeather/blackhole/backend/dto/`) already follow exactly this
  shape, including a response DTO implementing shared `Metadata`/`Durationable` marker
  interfaces to expose `creationDate()`/`duration()`/`translatable()` over a raw `metaData` map.
- **Otis** (`OneLiteFeatherNET/Otis`) - `OtisPlayerDTO` is the negative example this rule guards
  against: one record used as *both* the request body for `add`/`update` and the response body
  for every read endpoint - it even carries the server-assigned `uuid` field on the request side.
  Because there's no dedicated request DTO with a clear nullable-identifier convention,
  `update()` has to separately cross-check the body's `playerUuid` against a path-variable
  `owner` just to establish identity, and `add()` has no guard at all against a client supplying
  its own `uuid`/`playerUuid` on creation.

## See also

- `micronaut-controller-layer` - deserializes/serializes these DTOs at the HTTP boundary.
- `micronaut-service-layer` - takes a `*RequestDTO` in, hands a `*DTO` (or an outcome wrapping
  one) back out.
- `micronaut-openapi-contract` - references these DTOs in `@Schema(implementation = ...)`.
- `micronaut-error-response-contract` - this skill covers the success `*DTO` shape; that one
  covers pairing it with a sealed `*ResponseDTO` and error variant for the failure path.
