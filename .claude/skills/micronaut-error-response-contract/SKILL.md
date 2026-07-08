---
name: micronaut-error-response-contract
description: Error-response contract rule for Micronaut REST endpoints, in any Micronaut project - not tied to one specific codebase. A service method that can fail returns a sealed *ResponseDTO interface with a success record and one or more error records implementing a shared ErrorResponse marker interface (errorMessage() plus a generic ErrorResponseDTO fallback) - never a thrown domain exception for an expected error path. The controller (micronaut-controller-layer) pattern-matches/instanceof-checks the sealed result to pick the HTTP status but always returns the DTO as the response body, success or error - never an empty-body error response. A single last-resort global ExceptionHandler exists purely as a safety net for truly unexpected exceptions and itself returns the shared ErrorResponse DTO, never a raw exception/stack trace. Trigger whenever designing how an endpoint reports failure, adding a not-found/validation/conflict error path, or reviewing whether an exception is being thrown across the controller/service boundary instead of returned as a DTO.
---

# Micronaut error-response contract

## Where this sits in the layering

```
Service (micronaut-service-layer) returns *ResponseDTO (success | error) - always a DTO, never a thrown domain exception
Controller (micronaut-controller-layer)   matches on the sealed result, keeps the DTO as the body, only picks the HTTP status
Global ExceptionHandler                   last-resort safety net for truly unexpected exceptions; still returns a defined ErrorResponse DTO
```

This governs how failure is represented across the service/controller boundary and in the HTTP
response body itself - not the request/response shape covered by `micronaut-dto-contract`, and
not where business logic lives (`micronaut-service-layer`). Applies to any Micronaut backend, not
one specific codebase.

## The rule

- **A shared `ErrorResponse` marker interface** declares `errorMessage()` and provides one
  generic fallback implementation (`ErrorResponse.ErrorResponseDTO`) for the rare case a specific
  resource doesn't need its own tailored error DTO.
- **Every resource that has a defined error path gets a `sealed interface *ResponseDTO`**
  (`@Serdeable`, `@Schema`), with:
  - a nested success record (e.g. `WidgetDTO`) implementing the interface, carrying a static
    `createDTO(entity)` factory that maps the persistence entity to this DTO;
  - one or more nested error records (e.g. `WidgetErrorDTO`) implementing both the interface and
    `ErrorResponse`, carrying whatever fields make the failure diagnosable (an `errorMessage`
    string at minimum).
- **Service methods return the sealed interface type**, not a raw entity/`Optional` the caller
  has to convert, and not a thrown exception for an expected failure (not-found, invalid state,
  ownership mismatch, ...). If an operation genuinely cannot fail (e.g. an unconditional create),
  its method signature can narrow the return type to the success record directly - the type
  system then documents "this can't fail" without a runtime check anywhere.
- **The controller never throws and never returns an empty-body error.** It pattern-matches the
  sealed result (`instanceof` or a `switch`) purely to choose the `HttpResponse` status code, and
  passes the same DTO through as the body either way - `HttpResponse.notFound(result)`, not
  `HttpResponse.notFound()`. A client hitting a 404 or 400 from this API always gets a JSON body
  it can parse and show a message from.
- **A single, last-resort global exception handler stays as a safety net**, not the primary error
  path. It exists only for genuinely unexpected exceptions (a bug, a downstream outage) and must
  still return a defined `ErrorResponse` DTO - never a raw stack trace or an unstructured message
  leaking implementation details to the client.

## Review checklist

- [ ] Does a service method throw a custom/domain exception for an expected failure (not-found,
      validation, conflict)? -> replace it with an error variant of a sealed `*ResponseDTO`.
- [ ] Does the controller ever call `HttpResponse.notFound()`/`.badRequest()` etc. **without** a
      body? -> pass the service's returned DTO as the body instead.
- [ ] Does every error record implement the shared `ErrorResponse` interface, so a generic
      handler/consumer can always call `errorMessage()` regardless of which specific error DTO it
      got?
- [ ] Is the `*ResponseDTO` interface `sealed` (not a plain `interface`)? Sealing it makes the
      controller's `switch` exhaustive and prevents an untracked third implementation from
      appearing later.
- [ ] Does the global exception handler return a defined `ErrorResponse` DTO instead of leaking
      the exception's raw message/stack trace?

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

@Schema(description = "Response DTO for Widget")
@Serdeable
public sealed interface WidgetResponseDTO {

    @Schema(name = "ResponseWidgetDTO", description = "Widget data")
    @Serdeable
    record WidgetDTO(UUID id, String name) implements WidgetResponseDTO {

        public static WidgetDTO createDTO(WidgetEntity entity) {
            return new WidgetDTO(entity.getId(), entity.getName());
        }
    }

    @Schema(name = "WidgetErrorDTO", description = "Error message for Widget")
    @Serdeable
    record WidgetErrorDTO(String errorMessage) implements WidgetResponseDTO, ErrorResponse {
    }
}
```

```java
// service - returns the sealed type directly, never throws for an expected failure
public WidgetResponseDTO updateWidget(WidgetRequestDTO request) {
    Optional<WidgetEntity> existing = this.widgetRepository.findById(request.identifier());
    if (existing.isEmpty()) {
        return new WidgetResponseDTO.WidgetErrorDTO("Widget not found");
    }
    WidgetEntity saved = this.widgetRepository.update(request.toEntity());
    return WidgetResponseDTO.WidgetDTO.createDTO(saved);
}
```

```java
// controller - pattern-matches purely to pick the status, DTO is always the body
@Post("/update")
public HttpResponse<WidgetResponseDTO> update(@Valid @Body WidgetRequestDTO request) {
    WidgetResponseDTO result = this.widgetService.updateWidget(request);
    if (result instanceof WidgetResponseDTO.WidgetErrorDTO) {
        return HttpResponse.notFound(result);
    }
    return HttpResponse.ok(result);
}
```

## Observed in a real Micronaut codebase

**Vulpes-Backend** (`OneLiteFeatherNET/Vulpes-Backend`) is where this pattern comes from, and it
applies it consistently across every domain, not just one resource: `ItemModelResponseDTO`,
`ItemFlagResponseDTO`, `ItemLoreResponseDTO`, `ItemEnchantmentResponseDTO`, `FontModelResponseDTO`,
`FontStringResponseDTO`, `NotificationModelResponseDTO`, `AttributeModelResponseDTO`, and
`SoundResponseDTO` all follow the sealed-interface-plus-`ErrorResponse` shape, and
`ItemServiceImpl` returns the sealed type directly from every method that can fail (`updateItem`,
`deleteItem`, `createFlagById`, `deleteFlagById`, ... all return `new *ErrorDTO(...)` instead of
throwing). `ExceptionHandlerAdvice` is the last-resort global handler, itself returning
`ErrorResponse.ErrorResponseDTO`.

Two things worth noting rather than copying verbatim:

- `ItemFlagResponseDTO` is declared as a plain `interface`, not `sealed` - inconsistent with
  `ItemModelResponseDTO` and the rest. Prefer `sealed` for a new implementation of this pattern.
- `ItemController.getById()` is the one endpoint that breaks the pattern: it calls
  `itemService.findItemById(id)` (which returns a raw `Optional<ItemEntity>`) and then builds the
  `ItemModelResponseDTO.ItemModelDTO.createDTO(...)`/`.ItemModelErrorDTO(...)` itself, inline in
  the controller - exactly the entity-to-DTO-mapping-in-the-controller smell
  `micronaut-controller-layer`/`micronaut-service-layer` warn against. Every other endpoint in the
  same controller (`add`, `update`, `delete`) correctly has the service return the finished
  `ItemModelResponseDTO` and the controller only pattern-matches on it. Follow that majority
  shape, not `getById`'s.
- `ExceptionHandlerAdvice.handle(...)` always returns `HttpResponse.notFound(...)` regardless of
  the exception's actual nature - a real implementation should map exception type/severity to an
  appropriate status (400/404/500). The important part this skill cares about either way is that
  it returns a *DTO*, not a raw exception.

## See also

- `micronaut-service-layer` - where these `*ResponseDTO`-returning methods live; this skill
  refines "return small outcome types" into "make the DTO returned to the controller carry the
  error variant directly, since it's also the HTTP response body".
- `micronaut-controller-layer` - the pattern-match-and-map-to-`HttpResponse` step happens here,
  never entity-to-DTO conversion.
- `micronaut-dto-contract` - the request/response DTO shape this pattern's success variant builds
  on.
