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

## Client fingerprint (second signal)

`POST /evasion/fingerprint` (`PROXY` only) takes the brand, channels and legacy mods of a player, hashes them with
`ClientFingerprintHasher` (HMAC-SHA256 under a key HKDF-derived from `BLACKHOLE_FINGERPRINT_KEY`, a key of its own) and
stores only the hash with the owner hash (`client_fingerprint`). The raw traits are never stored, logged, published or
returned (`ClientFingerprintNoLeakTest`). Unset key: off outside dev/test (the route answers `503`, sightings say
`clientFingerprintEnabled: false`), a random key with a warning in dev/test. Treat the key as unrotatable in place; a
rotation is a new `scheme` and key pair. Plain vanilla/fabric/forge/neoforge traits are generic and not stored; a
fingerprint shared by more than `blackhole.evasion.fingerprint.max-owners` owners inside `window-days` (14) links nobody.

Sightings show `via` per linked owner: `IP`, `CLIENT` (same client setup only, a weak hint, never evidence) or `BOTH`.
Nothing in the ELO engine or a rule action reads it: a fingerprint match never punishes. `evasion.client_match` carries
hashed owners only. Fingerprints are swept after `retention-days` (30) and deleted on profile erasure.
