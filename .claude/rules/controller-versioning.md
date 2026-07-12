---
paths:
  - "backend/src/main/java/**/controller/**/*.java"
---

# Controller API versioning

REST controllers. API versioning follows Micronaut's built-in scheme
(`micronaut.router.versioning` in `application.yml`, docs.micronaut.io/latest/guide/#apiVersioning)
rather than a URI prefix: every business-facing controller carries a class-level
`@Version(ApiVersion.V1)`, and the version is resolved from the `X-API-VERSION` header or an
`api-version`/`v` query parameter — endpoint paths themselves stay version-less (e.g. `/punishment`,
not `/v1/punishment`). `default-version` is set to `1`, so callers that send no version at all
still route correctly. Infra/doc endpoints (health, prometheus, swagger) declare no `@Version`
and stay outside this scheme entirely. To introduce a breaking v2 of an endpoint, add a second
method annotated `@Version("2")` alongside the existing v1 method (same controller, same path) —
don't bump `ApiVersion.V1` itself or touch the URI.
