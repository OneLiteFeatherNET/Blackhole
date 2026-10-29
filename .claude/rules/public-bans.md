---
paths:
  - "backend/src/main/java/**/publicbans/**"
  - "backend/src/main/resources/db/migration/V32__public_ban_list.sql"
---

# Public ban list (`/public/bans`)

Detail: `openspec/changes/add-public-ban-list/` (design decisions 1 to 14, spec `public-ban-list`). Partner networks and
the website read it; it is not anonymous (see `public-api.md`).

- **Listing.** Opt-in per template: a template with a non-blank `publicReason` (ADMIN sets it, max 200, `PiiGuard`).
  Only `PUNISHMENT` rows are listed; kicks and templates without a reason never are. Reason and section kind are copied
  at apply time; later template edits do not change entries. The list never shows the staff `reason`.
- **Entry fields** (exactly these ten, `PublicBansDTO.Entry`): `hash`, `playerHash`, `hashScheme`, `reason`, `kind`,
  `status`, `issuedOn`, `permanent`, `expiresOn`, `endedOn`. Dates are UTC days only; no exact instant, no identifier,
  no owner, no name, no IP, no client name.
- **Entry hash.** `PublicBanHasher.entryHash`: 16 bytes of HMAC-SHA256 under a key derived from
  `BLACKHOLE_PUBLIC_BAN_KEY` (label `blackhole-public-ban-entry-v1`), over the internal identifier, 22 base64url chars,
  stored in `public_hash`. Rotating the key breaks every cited hash.
- **Player hash.** `playerHash` = lowercase SHA-256 hex of `blackhole-ban-list-v1:` + lowercase dashed UUID. Derived by
  PostgreSQL in the generated column `player_hash_v1` from `player_uuid`; the application never writes it and no key is
  involved. Test vector: `123e4567-e89b-12d3-a456-426614174000` -> `9efc1073aa30a316b4d4930dbf73bf852be666299041e930c9763de9f0c03029`.
  Null when the apply request carried no UUID. `hashScheme` follows the API version (`@Version("1")` is v1).
- **Player filter.** `?playerHash=` (64 hex, any case) filters in SQL on `player_hash_v1` (indexed); it never scans.
- **Status and retention.** Derived on read from the clock: REVOKED wins, then EXPIRED once the expiry instant has
  passed (no sweep needed), else ACTIVE. Ended entries are listed while `now < endedAt + BLACKHOLE_PUBLIC_BANS_RETENTION`
  (default `P90D`); after that the hash answers 404.
- **Cursor.** Keyset on `(public_issued_day DESC, public_hash DESC)`. `PublicBanCursor` signs `base64url(day:hash)` with
  the HMAC key of the hasher; a tampered cursor is 400 `INVALID_CURSOR`.
- **Summary.** Per reason and kind; a total below `BLACKHOLE_PUBLIC_BANS_SUMMARY_MIN_COUNT` (default 5) is `null` with
  `suppressed: true`.
- **Auth.** `BanListAccessFilter` (the only gate) covers exactly `/public/bans` and `/public/bans/**`. Order: HTTPS first
  (403 `HTTPS_REQUIRED`, no challenge; `X-Forwarded-Proto` honoured only from `BLACKHOLE_BAN_LIST_TRUSTED_INGRESS`),
  then Basic (401 `UNAUTHORIZED` with `WWW-Authenticate: Basic realm="blackhole-ban-list"` for every cause, Bearer
  included). Authentication runs before any lookup, so 401 comes before 404. Every answer is `Cache-Control: private, no-store`.
- **Clients.** `BLACKHOLE_BAN_LIST_CLIENTS` (names `[a-z0-9-]{1,32}`) and one file per name in
  `BLACKHOLE_BAN_LIST_SECRETS_DIR` (default `/etc/blackhole/ban-list`) holding an Argon2id PHC string. A missing file is a
  startup error outside `dev`/`test`. Rotation and client removal take effect at restart. Unknown names still run one
  verification against a dummy hash (constant work). The log line is `client=<name>` on success and
  `client=unauthenticated reason=<class>` on failure; the metric is `blackhole_ban_list_requests_total{client,route,status}`.
- **Creating a client hash (offline, never in the repo).** Parameters are fixed: Argon2id, m=19456 KiB, t=2, p=1, 32-byte
  output, 16-byte salt. With Python: `python3 -c "from argon2 import PasswordHasher, Type; print(PasswordHasher(memory_cost=19456, time_cost=2, parallelism=1, hash_len=32, salt_len=16, type=Type.ID).hash(input('password: ')))"`
  (package `argon2-cffi`). Use a random password of at least 32 bytes, store the hash in the client's Secret key, and
  give the password to the client out of band.
- **Template review.** `publicReason` is reviewed like the template: a validator refuses IPs, UUIDs and Ban IDs, but no
  rule can detect a player's name or a case number, so a reviewer reads each reason before an ADMIN saves it.
- **Erasure.** Erasing an owner clears the listing columns and `player_uuid` of their punishments; their entries leave the
  list and their hashes answer 404. Copies a partner already holds are out of reach.
- **Tests.** Route coverage: `RouteCoverageIntegrationTest` (`BASIC_CLIENT`). Integration: `PublicBansApiIntegrationTest`
  (seeds listed rows with SQL). F.I.R.S.T.: fixed `Clock`, `@TempDir` secrets, a counting verifier instead of timing.
