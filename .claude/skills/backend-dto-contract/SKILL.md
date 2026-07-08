---
name: backend-dto-contract
description: Request/response DTO contract convention for the Blackhole backend (Micronaut Serdeable records at the controller boundary, layered as controller -> service -> repository). Use whenever designing a new endpoint's request/response shape, adding a new DTO, or reviewing whether a DTO follows this codebase's conventions - pair a *RequestDTO (mutation input, nullable identifier field where null means create and set means update - the standing convention across this codebase) with a full *DTO for responses, both @Serdeable @ReflectiveAccess records with jakarta.validation constraints. Trigger whenever creating a new REST resource, adding a create/update endpoint pair, or reviewing a DTO's shape, nullability, or validation annotations.
---

# Backend DTO contract (Blackhole)

## Where this sits in the layering

```
Controller (backend-controller-layer) <-- *RequestDTO / *DTO (this skill) --> Service (backend-service-layer)
```

DTOs are the boundary contract: what a controller method deserializes from a request body and
what it serializes back. This layer doesn't change under `backend-controller-layer` /
`backend-service-layer` / `backend-openapi-contract` - it's the reference shape those other rules
build on.

## The rule

- **A mutation endpoint gets two DTOs, not one**: a `*RequestDTO` for input, a full `*DTO` for
  output. Don't reuse the response DTO as the request body just because the fields overlap - the
  request type is what a client is allowed to send, the response type is everything the server
  hands back.
- **The nullable-`identifier` convention decides create vs. update.** On the `*RequestDTO`,
  `identifier` is `@Nullable UUID`: `null` means "create a new one", a set value means "update
  this one". This is already the convention this codebase uses (see
  `PunishTemplateRequestDTO`) - don't invent a separate `isUpdate` flag or a different DTO per
  operation; reuse the same request DTO for both create and update endpoints.
- **Both are `@Serdeable @ReflectiveAccess` records.** Records keep them immutable and
  boilerplate-free; both annotations are required for Micronaut's GraalVM-friendly
  serialization/reflection to work on these types (mirrors the entire `dto` package today).
- **Field-level `jakarta.validation` constraints (`@NonNull`, `@NotBlank`, etc.) belong on the
  DTO**, not re-checked imperatively in the controller or service - Micronaut validates a
  `@Valid @Body` parameter before the controller method body even runs.
- **The full response `*DTO` may implement shared marker interfaces** (e.g. `Metadata`,
  `Durationable` from `phoca`) to expose derived accessors (`creationDate()`, `duration()`,
  `translatable()`) over the raw `metaData` map - this is the existing pattern for anything that
  carries a metadata blob, not something specific to punishment templates.

## Review checklist

- [ ] Does a create/update endpoint pair share one `*RequestDTO` with a nullable `identifier`,
      rather than separate Create/Update DTOs or a boolean flag?
- [ ] Is the response type a distinct, fuller `*DTO` rather than the request DTO reused as-is?
- [ ] Are both DTOs `@Serdeable @ReflectiveAccess` records with `jakarta.validation` constraints
      on the fields that need them?
- [ ] If the DTO carries a `metaData` map, does it implement the relevant `phoca` marker
      interface(s) instead of hand-rolling metadata accessors?

## Example

```java
@Serdeable
@ReflectiveAccess
public record PunishTemplateRequestDTO(
        @NonNull @NotBlank Map<String, Object> metaData,
        @NonNull @NotBlank String reason,
        @NonNull @NotBlank PunishType type,
        int eloDelta,
        @Nullable UUID identifier   // null = create, set = update
) {
}

@Serdeable
@ReflectiveAccess
public record PunishTemplateDTO(
        @NonNull @NotBlank Map<String, Object> metaData,
        @NonNull @NotBlank String reason,
        @NonNull @NotBlank PunishType type,
        int eloDelta,
        @Nullable UUID identifier
) implements Metadata, Durationable {
    // creationDate() / updateDate() / duration() derived from metaData, per the Metadata/Durationable contract
}
```

Treat `PunishTemplateDTO` / `PunishTemplateRequestDTO`
(`backend/src/main/java/net/onelitefeather/blackhole/backend/dto/`) as the canonical reference
pair - this rule is descriptive of what's already there, not a change to make.

## This pattern repeats beyond Blackhole (Otis)

`OneLiteFeatherNET/Otis`'s `OtisPlayerDTO` is the negative example this rule guards against: one
record used as *both* the request body for `add`/`update` and the response body for every read
endpoint - it even carries the server-assigned `uuid` field on the request side. Because there's
no dedicated request DTO with a clear nullable-identifier convention, `update()` has to
separately cross-check the body's `playerUuid` against a path-variable `owner` just to establish
identity, and `add()` has no guard at all against a client supplying its own `uuid`/`playerUuid`
on creation. Splitting into a `*RequestDTO` / `*DTO` pair with the nullable-identifier convention
(this rule) removes that whole cross-check.

## Generic example (any resource)

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

Same two-DTO shape as `PunishTemplateRequestDTO`/`PunishTemplateDTO` - only the field list
changes per resource. If the resource carries a metadata blob, the response DTO also implements
`Metadata`/`Durationable` as described above; if it doesn't, it's just a plain record like this.

## See also

- `backend-controller-layer` - deserializes/serializes these DTOs at the HTTP boundary.
- `backend-service-layer` - takes a `*RequestDTO` in, hands a `*DTO` (or an outcome wrapping one)
  back out.
- `backend-openapi-contract` - references these DTOs in `@Schema(implementation = ...)`.
