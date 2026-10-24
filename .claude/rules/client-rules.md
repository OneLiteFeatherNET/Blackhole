---
paths:
  - "backend/src/main/java/**/clientrule/**/*.java"
  - "velocity/src/main/java/**/clientrule/**/*.java"
  - "velocity/src/main/java/**/listener/ClientRuleListener.java"
  - "velocity/src/main/java/**/listener/CommandRuleListener.java"
---

# Client rules and client fingerprints

- **Honesty first.** Brand, mod and channel values are chosen by the client and can be faked; only the protocol
  version and commands are observed by the proxy. `NOTIFY` is the default action, and `TEMPLATE` on a brand, mod or
  channel is rejected unless `acknowledgedSpoofable` is set (cleared whenever the kind or action changes:
  `ClientRuleSpec` keeps it only where `ClientRuleValidator.requiresAcknowledgement` says it applies). Never let a
  fingerprint or a client-only link punish; nothing in the ELO engine reads it.
- **The proxy evaluates, the backend stores.** `RuleEngine` is a pure function on the proxy over its in-memory copy
  of the enabled rules (cache `blackhole:cache:v1:client-rules:all`, reload on `blackhole.clientrule.changed`). The
  backend never evaluates a rule; it stores rules (`clientrule/` services, one CTE statement per write, no
  `@Transactional`), counts hits and applies a TEMPLATE on a reported hit. No regular expressions anywhere: patterns
  are globs, matched by the linear `Globs`.
- **No raw trait is stored, logged, published or returned.** A hit holds rule key, owner hash, time, action, enforced,
  bypassed and server name. A fingerprint is a keyed hash (`ClientFingerprintHasher`, HKDF from
  `BLACKHOLE_FINGERPRINT_KEY`, its own key) plus the owner hash. `ClientRuleNoLeakTest` and
  `ClientFingerprintNoLeakTest` scan logs, events, rows and responses; keep them green when adding a field. The staff
  notice carries a player name, so it travels over Redis pub/sub only and is never stored.
- **Template application** goes through `PunishmentApplicationService.applyForRule`: the ELO system as source (so appeals
  treat it as automatic), `clientRule`/`automaticSource` in the metadata, the step's ELO cost charged, once while the
  player has an active punishment from the same rule, never for a bypassed player. A backend failure applies nothing.
- **Routes and roles** (matrix rows in `RouteCoverageIntegrationTest`): rules `GET` VIEWER+PROXY, `POST/PUT/DELETE` ADMIN;
  `/client-rule/hit` PROXY only; `GET /client-rule/{key}/hits` and `/client-rule/hits?owner=` VIEWER+ (PROXY denied);
  catalogue `PUT` PROXY only, `GET` VIEWER+ (PROXY denied); `POST /evasion/fingerprint` PROXY only. `catalogue`, `hits`
  and `hit` are reserved rule keys because they are routes. No app-level rate limiting.
- **Velocity fails open.** No rules loaded, backend down, an unknown server or an exception while checking never blocks a
  player. Only `ClientRuleListener`, `CommandRuleListener` import the Velocity events; the engine, the enforcers and
  `CommandDenial` take plain values. A command with signed arguments is denied only with SignedVelocity loaded
  (`CommandDenial`), otherwise delivered and recorded as not enforced. Re-check every Velocity API against the version in
  `settings.gradle.kts` when it changes (the API sources are published next to the jar).
- Retention (`blackhole.clientrules.hit-retention-days`, `blackhole.evasion.fingerprint.*`) is swept daily and rows are
  deleted with the profile erasure. User-facing description: the "Client rules and fingerprints" part of the root `README.md`.
