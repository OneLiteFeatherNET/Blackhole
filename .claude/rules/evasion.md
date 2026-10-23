---
paths:
  - "backend/src/main/java/**/evasion/**/*.java"
---

# Ban-evasion detection

Hashed IP correlation. Fully gated on `BLACKHOLE_EVASION_IP_SALT` being set —
`EvasionController` returns `503` rather than silently computing a weak/unsalted-equivalent hash
if the salt is unset. Treat the salt as effectively unrotatable in place; check
`IpCorrelationService`'s javadoc before ever changing it.

`GET /evasion/{owner}/sightings` (VIEWER) returns linked owner hashes only, built by
`EvasionSightingService` over the same detection window; it never exposes the token or an IP and
answers 200 with `detectionEnabled: false` when the salt is unset (a read, so no 503).
