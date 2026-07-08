---
name: micronaut-dto-contract
description: DTO contract convention for Micronaut REST resources, in any Micronaut project - not tied to one specific codebase. Each resource gets ONE sealed marker interface (e.g. WidgetDTO) nesting every variant as a record - CreateRequest (no identifier field), UpdateRequest (required identifier), Response (success data), and Error (implements a shared ErrorResponse interface). Never separate top-level *RequestDTO/*ResponseDTO types, and never a nullable identifier to distinguish create from update - the request variant's own type does that instead, and a service must always answer with the Error variant rather than throwing for an expected failure. Trigger whenever designing a new endpoint's request/response shape, adding a new DTO, reviewing a DTO's shape/nullability/validation annotations, or reviewing how an endpoint reports failure, in any Micronaut backend.
---

# Micronaut DTO contract

## Where this sits in the layering

```
Controller (micronaut-controller-layer) <-- WidgetDTO (this skill: one sealed marker, nested variants) --> Service (micronaut-service-layer)
```

`WidgetDTO` is the single boundary type a controller method deserializes a request from and
serializes a response into - both directions, success and error, all live under one sealed
interface per resource. This convention applies to any Micronaut backend, not one specific
codebase - it's the reference shape `micronaut-controller-layer`/`micronaut-service-layer`/
`micronaut-openapi-contract` build on.

## The rule

- **One `sealed interface` per resource, not separate `*RequestDTO`/`*ResponseDTO` top-level
  types.** Every request and response shape for that resource is a nested record implementing
  the same marker interface, so the whole resource's wire contract is readable from one file.
- **Nest at minimum these variants:**
  - `CreateRequest` - the fields needed to create a new instance. It has **no identifier field at
    all** - identity is server-assigned, so there's nothing to null-check.
  - `UpdateRequest` - the same fields plus a **required (non-null)** identifier. Whether an
    operation is a create or an update is now a fact about which *type* the client sent, not
    something anyone has to branch on at runtime by inspecting a nullable field.
  - `Response` - the success shape, with a static `createDTO(entity)` factory mapping the
    persistence entity to this record.
  - `Error` - implements both the marker interface and a shared cross-resource `ErrorResponse`
    interface (`errorMessage()`), so any resource's failure can be handled generically.
- **Give every nested variant an explicit, resource-qualified `@Schema(name = "...")`.** Swagger
  generates OpenAPI component names from the record's simple name by default - if every
  resource's marker interface nests a record literally called `Response`/`Error`/
  `CreateRequest`, every resource's schema collides in the generated spec. Name them
  `WidgetResponse`, `WidgetError`, `WidgetCreateRequest`, etc.
- **Both the interface and every nested record are `@Serdeable`**; field-level
  `jakarta.validation` constraints (`@NonNull`, `@NotBlank`, etc.) belong on the request variants,
  not re-checked imperatively in the controller or service.
- **Never throw for an expected failure.** A service method that can fail (not-found, invalid
  state, ownership mismatch, ...) returns the resource's `Error` variant directly instead of
  throwing a domain exception - see `micronaut-service-layer` for where that method lives and
  `micronaut-controller-layer` for how the controller turns it into an `HttpResponse` that still
  carries the `Error` DTO as its body. A single last-resort global exception handler can remain
  for genuinely unexpected exceptions, but it too must return a defined `ErrorResponse` DTO -
  never a raw stack trace.
- **An operation that genuinely cannot fail may return `Response` directly**, narrowing the
  method's return type instead of the full sealed interface - the type system then documents
  "this can't fail" without a runtime check anywhere.

## Review checklist

- [ ] Is there one sealed interface per resource, not separate top-level `*RequestDTO`/
      `*ResponseDTO` files?
- [ ] Does `CreateRequest` omit the identifier field entirely (not `@Nullable`), and does
      `UpdateRequest` require it (non-null)?
- [ ] Does every nested variant have an explicit, resource-qualified `@Schema(name = "...")` so
      OpenAPI component names don't collide across resources?
- [ ] Does the `Error` variant implement the shared `ErrorResponse` interface?
- [ ] Does any service method throw a custom exception for an expected failure instead of
      returning the `Error` variant?
- [ ] Does the controller ever return an error `HttpResponse` **without** a body? -> pass the
      `Error` DTO through instead.

## Example (any resource)

```java
package net.example.app.domain.error;

public interface ErrorResponse {

    String errorMessage();

    @Serdeable
    record ErrorResponseDTO(String errorMessage) implements ErrorResponse {
    }
}
```

```java
package net.example.app.domain.widget;

@Schema(description = "DTO for Widget")
@Serdeable
public sealed interface WidgetDTO {

    @Schema(name = "WidgetCreateRequest")
    @Serdeable
    record CreateRequest(
            @NonNull @NotBlank String name
    ) implements WidgetDTO {
    }

    @Schema(name = "WidgetUpdateRequest")
    @Serdeable
    record UpdateRequest(
            @NonNull UUID id,
            @NonNull @NotBlank String name
    ) implements WidgetDTO {
    }

    @Schema(name = "WidgetResponse")
    @Serdeable
    record Response(UUID id, String name) implements WidgetDTO {

        public static Response createDTO(WidgetEntity entity) {
            return new Response(entity.getId(), entity.getName());
        }
    }

    @Schema(name = "WidgetError")
    @Serdeable
    record Error(String errorMessage) implements WidgetDTO, ErrorResponse {
    }
}
```

```java
// service (micronaut-service-layer) - takes the specific request variant it needs, returns the marker interface
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
```

```java
// controller (micronaut-controller-layer) - pattern-matches purely to pick the status, DTO is always the body
@Post("/")
public HttpResponse<WidgetDTO> create(@Valid @Body WidgetDTO.CreateRequest request) {
    return HttpResponse.ok(this.widgetService.createWidget(request));
}

@Post("/update")
public HttpResponse<WidgetDTO> update(@Valid @Body WidgetDTO.UpdateRequest request) {
    WidgetDTO result = this.widgetService.updateWidget(request);
    if (result instanceof WidgetDTO.Error) {
        return HttpResponse.notFound(result);
    }
    return HttpResponse.ok(result);
}
```

## Observed in real Micronaut codebases (prior art, not the exact shape)

Neither real example below merges request and response into one marker interface the way this
skill prescribes - both keep request and response as two separate types, and use a nullable
identifier or manual cross-checks instead of distinct Create/Update request types. They still
demonstrate the two underlying ideas this skill combines - a sealed response marker with a shared
`ErrorResponse` error variant, and a dedicated request shape - the unification into a single
per-resource interface is this project's own step further, not something either repo does yet.

- **Vulpes-Backend** (`OneLiteFeatherNET/Vulpes-Backend`) is where the sealed-response half of
  this pattern comes from: `ItemModelResponseDTO`, `ItemFlagResponseDTO`, `ItemLoreResponseDTO`,
  `ItemEnchantmentResponseDTO`, `FontModelResponseDTO`, `FontStringResponseDTO`,
  `NotificationModelResponseDTO`, `AttributeModelResponseDTO`, and `SoundResponseDTO` all nest a
  success record and an `ErrorResponse`-implementing error record under a response interface
  (mostly `sealed` - `ItemFlagResponseDTO` is a plain, non-sealed `interface`, worth not copying),
  and `ItemServiceImpl` returns that type directly from every method that can fail instead of
  throwing. Its request side stays a separate flat `ItemModelDTO` record using Bean Validation
  groups (`@Null(groups = Create.class)`/`@NotNull(groups = Update.class)` on one shared `id`
  field) to distinguish create vs. update, rather than two distinct request types.
- **Blackhole** (`OneLiteFeatherNET/Blackhole`) - `PunishTemplateRequestDTO`/`PunishTemplateDTO`
  are two separate flat records with a nullable-`identifier` convention deciding create vs.
  update; there's no sealed response/error type at all yet (error responses today are empty-body
  `HttpResponse.notFound()`/`.badRequest()`).

## See also

- `micronaut-controller-layer` - deserializes/serializes `WidgetDTO` variants at the HTTP
  boundary and pattern-matches on `Error` to pick the response status.
- `micronaut-service-layer` - the layer that constructs `Response`/`Error` variants and never
  throws for an expected failure.
- `micronaut-openapi-contract` - references these nested variants in
  `@Schema(implementation = ...)`.
