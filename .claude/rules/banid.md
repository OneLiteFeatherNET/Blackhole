---
paths:
  - "backend/src/main/java/**/banid/**/*.java"
---

# Ban ID

- 8 characters of Crockford base32 from `SecureRandom` (`BanIdGenerator`, seam for tests). Typed input
  goes through `BanIdCodec.normalize`; the plain value lives in memory only.
- Stored in `punishments` as `ban_id_hash` (HMAC-SHA256, unique, the lookup) and `ban_id_enc`
  (AES-256-GCM, punishment identifier as AAD, the staff reveal). Both keys derive (HKDF) from
  `BLACKHOLE_BANID_KEY`; `BanIdKeyGuard` refuses an unset key outside `dev`/`test`; there an unset key means a random key per run (WARN, Ban IDs do not survive a restart). No key material is ever committed, tests use `TestKeys`.
- Never log it, put it in an event, metric label, URL, query or DTO other than `GET /punishment/{ref}/ban-id`
  (`MODERATOR`, `PROXY`, `no-store`) and the body of `POST /punishment/lookup` (same roles, uniform 404, `no-store`). `BanIdNoLeakTest` scans logs, events and metrics. Log and error
  messages carry identifiers and SQLState only.
- `BanIdService.issue` never throws (a punishment must not fail for it); `BanIdBackfill` heals the
  rest (idempotent, batched, gauge `blackhole.banid.missing`). The vanilla import relies on it.
- A candidate equal to a legacy `short_id` is discarded. Legacy hex short ids stay staff-only.
- Notes reject a `#` plus 8 alphabet characters (`PiiGuard.banIdCandidates`); a hex-only token is
  rejected only if it is an issued Ban ID, because staff name players by legacy short id.
- Reissue and leak state live in `casetimeline/` (`CaseChanges.reissueBanId|markBanIdLeaked`, one CTE each); the service
  draws the new value with `BanIdService.draw` (hash + ciphertext, never stored by it) and retries a hash collision.
- Key rotation: `BanIdReissueAll` (started only by `BanIdReissueAllRunner` when `blackhole.banid.reissue-all` / `BLACKHOLE_BANID_REISSUE_ALL` is
  `true`, once per start, in the background) reissues every Ban ID the current key cannot decrypt, via
  `CaseChanges.reissueBanIdAtGeneration` (one CTE: generation guard, sessions deleted, `ban_id.reissued` with reason `KEY_ROTATION`). No extra state:
  a Ban ID that decrypts is current, so it is idempotent and resumable. Counters `blackhole.banid.reissue.reissued|failed`; logs counts only.
