# API audit: pagination and RFC 9457 problem details

Status: audit only. No product code changed. No proposal yet (step 2).
Date: 2026-10-25.

## Scope and method

- Backend: `backend/src/main/java/net/onelitefeather/blackhole/backend/`. Controller and `*Api` paths below are relative to this base.
- Spec: `client/specs/blackhole-api-0.0.40.yml` (the Java client source, `client/build.gradle.kts:23`).
- Clients: `web/src/api/*`, `portal/server/lib/bff.ts`, `velocity` (`HttpBackendGateway` and generated-client call sites), `discord` (`HttpBackendGateway`).
- Read-only. Gradle and Docker were not touched.

Version note: `gradle.properties` pins `micronautVersion=4.10.2`. The root plugin `io.micronaut.platform.catalog` is 5.0.2. `openspec/config.yaml` says "Micronaut 5". The build is Micronaut 4.10.2; the openspec context text is stale and should be fixed in step 2.

Counts (verified against the inventory and the spec):

- 142 endpoints in code, 141 operations in the spec.
- One code-only route: `POST /admin/import/vanilla` (`imports/controller/VanillaImportController.java:44`, `:46`).
- No spec-only routes.
- 37 routes return a list (bare array, page, list page, cursor page or a DTO whose main payload is a list).
- 13 of those 37 are paginated today. 24 are not.

## 1. Endpoint table

Columns: method | path | roles | list? | pagination today | notes (controller `file:line`).

Roles: V VIEWER, M MODERATOR, A ADMIN, P PROXY, D DISCORD, PS PLAYER_SESSION, ANON anonymous, AUTH any authenticated. Every controller is `@Version(ApiVersion.V1)` (`controller/ApiVersion.java:13`).

"Pagination" is one of: `framework` (Micronaut `Pageable`, `page`/`size`/`sort`, max 100, default 100 because `defaultPageSize` is unset), `custom`, `cursor`, `limit` only, `cap` (fixed server-side maximum, no client control), or `none`.

| # | method | path | roles | list? | pagination today | notes |
|---|---|---|---|---|---|---|
| 1 | GET | /punishment/{reference}/ban-id | M,P | no | - | BanIdController:39 |
| 2 | GET | /player/resolve/{name} | V,P | no | - | PlayerLookupController:33 |
| 3 | PUT | /cloudnet/catalogue | P | no | - | CatalogueController:31 |
| 4 | GET | /cloudnet/catalogue | V | yes (DTO) | none | snapshot DTO, CatalogueController:41 |
| 5 | GET | /discord/staff-links | D | yes | none | DiscordStaffLinksController:32; DiscordLinkService.java:212 |
| 6 | GET | /discord/notifications/report/{identifier} | D | no | - | DiscordNotificationController:32 |
| 7 | GET | /discord/notifications/appeal/{identifier} | D | no | - | DiscordNotificationController:38 |
| 8 | POST | /discord/leak-check | D | no | - | DiscordLeakCheckController:41 |
| 9 | POST | /discord/link/confirm | D | no | - | DiscordLinkController:30 |
| 10 | GET | /discord/link/{discordUserId} | D | yes (links) | none | DiscordLinkController:36; per user |
| 11 | DELETE | /discord/link/{discordUserId} | D | no | - | DiscordLinkController:42 |
| 12 | POST | /player-discord-link/code | P | no | - | PlayerDiscordLinkController:30 |
| 13 | POST | /staff-discord-link/code | V | no | - | StaffDiscordLinkController:30 |
| 14 | GET | /staff-discord-link/me | V | no | - | StaffDiscordLinkController:36 |
| 15 | DELETE | /staff-discord-link/me | V | no | - | StaffDiscordLinkController:42 |
| 16 | GET | /admin/discord-link | A | yes | none | AdminDiscordLinkController:31; DiscordLinkService.java:219 |
| 17 | DELETE | /admin/discord-link/{id} | A | no | - | AdminDiscordLinkController:37 |
| 18 | POST | /discord/tickets | D | no | - | DiscordTicketController:75 |
| 19 | GET | /discord/tickets | D | yes | cap | `state` filter; `listLimit` 200 (`DiscordTicketProperties.java:22`, `application.yml:141`, `TicketService.java:171`); no cursor, page or total. DiscordTicketController:81 |
| 20 | GET | /discord/tickets/{id} | D | no | - | DiscordTicketController:87 |
| 21 | PATCH | /discord/tickets/{id} | D | no | - | DiscordTicketController:93 |
| 22 | POST | /discord/tickets/{id}/attachments | D | no | - | DiscordTicketApi:204 |
| 23 | GET | /discord/tickets/{id}/attachments/{attachmentId} | D | no | - | DiscordTicketApi:222 (binary) |
| 24 | GET | /discord/tickets/{id}/outbox | D | yes | cursor (`afterSeq` + `limit`) | default 50 (`DiscordTicketProperties.java:20`, `application.yml:138`), max 200 (`:21`, `application.yml:139`, clamp `TicketService.java:376`); no total. DiscordTicketController:99 |
| 25 | POST | /discord/tickets/{id}/messages | D | no | - | DiscordTicketController:105 |
| 26 | POST | /discord/tickets/{id}/links | D | no | - | DiscordTicketController:111 |
| 27 | POST | /discord/tickets/{id}/close | D | no | - | DiscordTicketController:117 |
| 28 | POST | /discord/tickets/{id}/protocol/ensure | D | no | - | DiscordTicketController:123 |
| 29 | POST | /elo/chat | M,P | no | - | EloController:40 |
| 30 | GET | /elo/{owner} | V,P | yes (DTO) | none | fixed section set, EloController:46 |
| 31 | POST | /elo/{owner}/adjust | M | no | - | EloController:59 |
| 32 | GET | /elo/{owner}/history | V | yes | framework | `section` + pageable, max 100. Spec says array. EloController:52, EloApi:62 |
| 33 | POST | /evasion/fingerprint | P | no | - | EvasionController:60 |
| 34 | POST | /evasion/record | M,P | no | - | EvasionController:79 (mapping on controller) |
| 35 | GET | /evasion/{owner}/sightings | V | yes (linkedOwners) | cap 50 | `truncated` flag, EvasionSightingService.java:27, :71. EvasionController:48 |
| 36 | POST | /evidence | M,P | no | - | EvidenceController:48 (bare `@Controller`) |
| 37 | POST | /evidence/file | M,P | no | - | EvidenceController:54 |
| 38 | GET | /punishment/{reference}/evidence | V,P | yes | framework | `includeInactive` + pageable. EvidenceController:70, EvidenceApi:108. Spec `pageable` at spec:4316 |
| 39 | GET | /punishment/{reference}/evidence/count | V,P | no | - | EvidenceController:76 |
| 40 | GET | /evidence | V,P | yes | framework | `owner`, `includeInactive` + pageable. EvidenceController:82, EvidenceApi:143. Spec `pageable` at spec:2882 |
| 41 | GET | /evidence/{identifier} | V | no | - | EvidenceController:88 |
| 42 | GET | /evidence/{identifier}/content | V | no | - | EvidenceController:94 (binary) |
| 43 | DELETE | /evidence/{identifier} | A | no | - | EvidenceController:106 |
| 44 | DELETE | /evidence/owner/{owner} | A | no | - | EvidenceController:112 |
| 45 | POST | /case/{reference}/attachments | M | no | - | ChatAttachmentController:39 (multipart) |
| 46 | POST | /case/{reference}/evidence-shares/preview | V | no | - | EvidenceShareController:34 (POST with role V) |
| 47 | POST | /case/{reference}/evidence-shares | M | no | - | EvidenceShareController:40 |
| 48 | GET | /case/{reference}/evidence-shares | V | yes | none | `EvidenceShareService.java:174`, `JdbcEvidenceShareStore.java:64` (no LIMIT). EvidenceShareController:46 |
| 49 | POST | /case/{reference}/evidence-shares/{shareId}/revoke | M | no | - | EvidenceShareController:52 |
| 50 | GET | /section | V,P | yes | none | bare array, SectionController:45; SectionService.java:117-122 |
| 51 | POST | /section | A | no | - | SectionController:33 |
| 52 | POST | /section/update | A | no | - | SectionController:39 |
| 53 | GET | /section/{key} | V,P | no | - | SectionController:51 |
| 54 | POST | /section/{key}/disable | A | no | - | SectionController:57 |
| 55 | POST | /profile | M | no | - | ProfileController:123 (no Api interface) |
| 56 | POST | /profile/update/{owner} | M | no | - | ProfileController:168 |
| 57 | DELETE | /profile/delete/{owner} | A | no | - | ProfileController:218 |
| 58 | GET | /profile | V,P | yes | framework | pageable, max 100. Spec says array (spec:3293 pageable). ProfileController:261 |
| 59 | GET | /profile/{owner} | V,P | yes (DTO) | none | history embedded, ProfileController:298 |
| 60 | POST | /profile/{owner}/session | M,P | no | - | ProfileController:343 |
| 61 | GET | /case | V | yes | custom (page/size) | 0-based page, default 20, max 100 (`CaseService.java:58-59`, clamp `:171`); total and totalPages; no sort. CaseController:40, CaseApi:41 |
| 62 | GET | /case/{reference} | V | no | - | CaseController:49 |
| 63 | GET | /case/{reference}/timeline | V | yes | cursor (opaque) + size | default 50, max 200 (`CaseService.java:56-57`, `:147`); no total. CaseController:55, CaseApi:84 |
| 64 | PATCH | /case/{reference} | M | no | - | CaseController:64 |
| 65 | POST | /case/{reference}/notes | M | no | - | CaseController:70 |
| 66 | PUT | /case/{reference}/notes/{noteId} | M | no | - | CaseController:76 |
| 67 | POST | /case/{reference}/notes/{noteId}/redact | A | no | - | CaseController:82 |
| 68 | GET | /case/{reference}/events | V | stream | - | SSE, `Last-Event-ID`. CaseLiveController:35 |
| 69 | POST | /case/{reference}/participants/{participantId}/erase | A | no | - | CaseParticipantController:32 |
| 70 | GET | /case/{reference}/protocol | V | yes (items) | none | CaseProtocolService.java:43-50, CaseProtocolController:38 |
| 71 | GET | /case/{reference}/protocol/{evidenceId} | V | no | - | CaseProtocolController:44 |
| 72 | GET | /case/{reference}/protocol/{evidenceId}/download | V | no | - | CaseProtocolController:50 (file) |
| 73 | POST | /case/{reference}/messages | M | no | - | CaseConversationController:38 |
| 74 | PUT | /case/{reference}/messages/{messageId} | M | no | - | CaseConversationController:44 |
| 75 | DELETE | /case/{reference}/messages/{messageId} | M | no | - | CaseConversationController:50 |
| 76 | POST | /case/{reference}/messages/{messageId}/redact | A | no | - | CaseConversationController:56 |
| 77 | POST | /case/{reference}/transcript/erase-player | A | no | - | CaseConversationController:62 |
| 78 | POST | /case/{reference}/ban-id/leak | M | no | - | CaseConversationController:68 |
| 79 | POST | /case/{reference}/ban-id/reissue | A | no | - | CaseConversationController:74 |
| 80 | GET | /case/{reference}/transcript | A | no | - | file, CaseConversationController:80 |
| 81 | POST | /admin/import/vanilla | A | yes (result) | none | multipart. Code only, not in spec. VanillaImportController:44, :46 |
| 82 | GET | /admin/import/punishments | A | yes (groups) | none | ImportedPunishmentService.java:67-76, ImportedPunishmentController:32 |
| 83 | POST | /admin/import/punishments/retemplate | A | no | - | ImportedPunishmentController:38 |
| 84 | POST | /appeal | M,P | no | - | AppealController:63 |
| 85 | GET | /appeal | V | yes | framework | pageable, max 100. Spec says array (spec:212). AppealController:85, AppealApi:56 |
| 86 | POST | /appeal/{identifier}/review | M | no | - | AppealController:92 |
| 87 | GET | /appeal-conditions | V | no | - | AppealConditionsController:32 |
| 88 | GET | /appeal-conditions/versions | V | yes | none | AppealConditionsService.java:42-43, JdbcAppealConditionsStore.java:50. AppealConditionsController:38 |
| 89 | POST | /appeal-conditions | A | no | - | AppealConditionsController:44 |
| 90 | GET | /dashboard/summary | V | yes (DTO) | cap 10 | latest escalations, DashboardService.java:21. DashboardController:24 |
| 91 | POST | /public/session | ANON | no | - | uniform 401 on failure. PublicController:58 |
| 92 | GET | /public/session | PS | no | - | PublicController:64 |
| 93 | DELETE | /public/session | PS | no | - | PublicController:70 |
| 94 | GET | /public/punishment | PS | no | - | PublicController:77 |
| 95 | POST | /public/appeal | PS | no | - | PublicController:83 |
| 96 | GET | /public/appeal/conditions | PS | no | - | PublicController:89 |
| 97 | GET | /public/appeal | PS | no | - | PublicController:95 |
| 98 | GET | /public/conversation | PS | yes (entries, cursor) | cursor only | server page size 200 (`PublicConversationService.java:53,60`, `application.yml:171`); no client size; no total. PublicController:101, PublicApi:148 |
| 99 | POST | /public/messages | PS | no | - | PublicController:107 |
| 100 | GET | /public/appeal/protocol | PS | no | - | file, PublicController:113 |
| 101 | GET | /public/conversation/events | PS | stream | - | SSE, `Last-Event-ID`. PublicLiveController:38 |
| 102 | POST | /public/attachments | PS | no | - | multipart, PublicAttachmentController:43 |
| 103 | GET | /public/attachments/{id} | PS | no | - | PublicAttachmentController:56 (file) |
| 104 | DELETE | /public/attachments/{id} | PS | no | - | PublicAttachmentController:66 |
| 105 | GET | /client-rule | V,P | yes | none | ClientRuleService.java:113-114, JdbcClientRuleStore.java:161 (no LIMIT). ClientRuleController:53 |
| 106 | POST | /client-rule | A | no | - | ClientRuleController:35 |
| 107 | PUT | /client-rule/{key} | A | no | - | ClientRuleController:41 |
| 108 | DELETE | /client-rule/{key} | A | no | - | ClientRuleController:47 |
| 109 | GET | /client-rule/{key} | V,P | no | - | ClientRuleController:59 |
| 110 | POST | /client-rule/hit | P | no | - | ClientRuleHitController:33 |
| 111 | GET | /client-rule/{key}/hits | V | yes | limit only | default 100, max 500; no offset. ClientRuleHitService.java:32-33, :110; ClientRuleHitController:39 |
| 112 | GET | /client-rule/hits | V | yes | limit only | owner + limit; same bounds. ClientRuleHitController:45 |
| 113 | PUT | /client-rule/catalogue | P | no | - | ClientRuleCatalogueController:31 |
| 114 | GET | /client-rule/catalogue | V | yes (DTO) | none | snapshot. ClientRuleCatalogueController:41 |
| 115 | GET | /auth/me | AUTH | yes (roles) | none | small DTO, AuthController:30 |
| 116 | GET | /admin/staff-actor | A | yes | none | StaffActorService.java:46-47 (findAll). StaffActorController:32 |
| 117 | POST | /admin/staff-actor/{oid}/anonymize | A | no | - | StaffActorController:38 |
| 118 | POST | /staff-link/code | V | no | - | StaffLinkController:32 |
| 119 | GET | /staff-link/me | V | no | - | StaffLinkController:38 |
| 120 | DELETE | /staff-link/me | V | no | - | StaffLinkController:44 |
| 121 | POST | /staff-link/confirm | P | no | - | StaffLinkController:50 |
| 122 | GET | /staff-link/by-minecraft/{uuid} | P | no | - | StaffLinkController:56 |
| 123 | GET | /admin/staff-link | A | yes | none | StaffLinkService.java:175-176. AdminStaffLinkController:32 |
| 124 | DELETE | /admin/staff-link/{oid} | A | no | - | AdminStaffLinkController:38 |
| 125 | GET | /punishment/active | V | yes | custom (page/size, sort) | 0-based; default 25, max 100 (`ActivePunishmentService.java:25-26,53`); sort and direction; total and totalPages. ActivePunishmentController:40, ActivePunishmentApi:38 (11 query params) |
| 126 | POST | /punishment/apply | M,P | no | - | PunishmentEntityController:39 |
| 127 | POST | /punishment/preview | M | no | - | PunishmentEntityController:45 |
| 128 | POST | /punishment/revoke | M,P | no | - | PunishmentEntityController:51 |
| 129 | GET | /punishment | V,P | yes | framework | pageable, max 100. Spec says array (spec:3947). PunishmentEntityController:57 |
| 130 | GET | /punishment/short/{shortId} | V,P | yes (matches) | none | bounded only by short-id collisions. PunishmentEntityController:63 |
| 131 | POST | /punishment/lookup | M,P | no | - | PunishmentEntityController:73 |
| 132 | POST | /template | A | no | - | PunishmentTemplateController:45 |
| 133 | POST | /template/update | A | no | - | PunishmentTemplateController:51 |
| 134 | DELETE | /template/delete/{identifier} | A | no | - | PunishmentTemplateController:57 |
| 135 | GET | /template | V,P | yes | framework | pageable, max 100. Spec is object `PunishTemplatePage` (matches the Page shape). Spec pageable at spec:4864. PunishmentTemplateController:63 |
| 136 | GET | /template/{identifier} | V,P | no | - | PunishmentTemplateController:69 |
| 137 | POST | /report | M,P | no | - | ReportController:50 |
| 138 | GET | /report | V | yes | framework | `reportedHash` + pageable. Spec says array; pageable at spec:4383. ReportController:60 |
| 139 | GET | /report/{identifier} | V,P | no | - | ReportController:68 |
| 140 | POST | /report/{identifier}/resolve | M | no | - | ReportController:75 |
| 141 | GET | /report/feedback/{owner} | P | yes | none | ReportFeedbackService.java:66-70. ReportFeedbackController:31 |
| 142 | POST | /report/feedback/{identifier}/ack | P | no | - | ReportFeedbackController:37 |

### Pagination summary

Paginated today (13):

- Framework `Pageable` (8): `/elo/{owner}/history`, `/punishment/{reference}/evidence`, `/evidence`, `/profile`, `/appeal`, `/punishment`, `/template`, `/report`.
- Custom or cursor (5): `/case` (page/size), `/punishment/active` (page/size/sort), `/case/{ref}/timeline` (cursor), `/discord/tickets/{id}/outbox` (seq cursor), `/public/conversation` (cursor).

Unpaginated (24):

- No bound at all (19): `/discord/staff-links`, `/discord/link/{discordUserId}`, `/admin/discord-link`, `/elo/{owner}` (sections), `/case/{ref}/evidence-shares`, `/case/{ref}/protocol`, `/admin/import/punishments`, `/appeal-conditions/versions`, `/profile/{owner}` (history), `/client-rule`, `/client-rule/catalogue`, `/cloudnet/catalogue`, `/auth/me`, `/admin/staff-actor`, `/admin/staff-link`, `/punishment/short/{shortId}`, `/report/feedback/{owner}`, `/section`, `/admin/import/vanilla`.
- Fixed cap or limit only (5): `/discord/tickets` (cap 200, no parameter), `/evasion/{owner}/sightings` (cap 50 with `truncated`), `/dashboard/summary` (latest 10), `/client-rule/hits` and `/client-rule/{key}/hits` (`limit` only, max 500, no offset).

### Spec mismatches (`client/specs/blackhole-api-0.0.40.yml`)

1. Code only: `POST /admin/import/vanilla`.
2. Query parameter: the spec documents one `pageable` parameter on eight operations (spec lines 212, 2755, 2882, 3293, 3947, 4316, 4383, 4864). The code binds `page`, `size` and `sort`. The spec does not describe the real names.
3. List shape: the spec says `type: array` for `/appeal` (spec:224), `/elo/{owner}/history` (spec:2767), `/profile` (spec:3305), `/punishment` (spec:3959) and `/report` (spec:4402). The code returns a Micronaut `Page`. The `Api` interfaces use `@ArraySchema(arraySchema = @Schema(implementation = Page.class))` (e.g. `appeal/controller/AppealApi.java:44-55`, `report/controller/ReportApi.java:52-60`), which is what the generator reads.
4. Versioning: the spec has no `X-API-VERSION` header and no `api-version` parameter, although every operation is `@Version("1")`.

## 2. Error formats today

### Shapes

- Internal variants implement `ErrorResponse` (`response/ErrorResponse.java:8`, method `errorMessage()`). Most also carry `ErrorKind kind`, which maps to the HTTP status in `response/ErrorKind.java:11-43`: VALIDATION 400, NOT_FOUND 404, CONFLICT 409, UNAUTHORIZED 401, FORBIDDEN 403, INVALID_CODE 400, UNAVAILABLE 503. Each resource's `Error` record implements it (for example `banid/dto/BanIdDTO.java:32`, `punishment/dto/PunishmentDTO.java:189`). Controllers map `error.kind().status()` directly. `ReportErrorDTO` is top-level (`report/dto/ReportErrorDTO.java:17`).
- Public variants implement `PublicError` (`publicapi/dto/PublicError.java:6-14`: `code()`, `availableFrom()`, `kind()`). They do not extend `ErrorResponse`. Wire body is `PublicErrorBody(code, availableFrom)` (`publicapi/dto/PublicErrorBody.java:17`).
- Non-conforming: `profile/dto/PunishProfileResponse.java:16` nests its own `ErrorResponse(message)` that does not implement the marker. `ProfileController` at `:173`, `:226`, `:309` returns it. The `ErrorResponse` javadoc (`response/ErrorResponse.java:6`) cites a stale package.
- Other shapes in use: `{"reason":"groups-overage"}` (403), `{"code":"VALIDATION","availableFrom":null}` (400 on `/public/**`), and `{"code":"INVALID_BAN_ID","availableFrom":null}` (401 on login).

### Global handlers

- No `@Error`, no `ExceptionHandler` bean and no `global=true` mapping exists in `backend/src/main`.
- `security/GroupsOverageExceptionHandler.java:28-29`, `:50-56`: replaces `DefaultAuthorizationExceptionHandler`. Returns 403 `{"reason":"groups-overage"}` for a groups overage (`:53`), else delegates to the default.
- `publicapi/controller/PublicErrorFilter.java:18-36`: `@ServerFilter("/public/**")`. Rewrites framework 400s. On `POST /public/session` it becomes 401 `INVALID_BAN_ID` (`:28-31`). Elsewhere `{"code":"VALIDATION"}` (`:33`). Always sets `Cache-Control: no-store` (`:35`).
- `security/ActorResolver.java:53` throws `HttpStatusException` 401 with "the token carries no valid object id". Body comes from the framework default (unverified).

### Bare or empty bodies

- Plain strings: `appeal/controller/AppealController.java:96`, `:99`, `:100`, `:101` (409, 400, 403, 422 with `result.message()`), `imports/controller/VanillaImportController.java:62` (400).
- No body: `evasion/controller/EvasionController.java:81` (503), `report/controller/ReportController.java:54` (429), `casetimeline/controller/CaseLiveController.java:42-43` and `publicapi/controller/PublicLiveController.java:44-46` (403, 404, 503).

### Validation (400)

- `@Validated` controllers: `banid/controller/BanIdController.java:21`, `cloudnet/controller/CatalogueController.java:17`, `imports/controller/ImportedPunishmentController.java:18`, `evidence/controller/EvidenceController.java:30`, `evasion/controller/EvasionController.java:79`.
- `@Valid @Body` on Api interfaces: `evidence/controller/EvidenceApi.java:54`, `appeal/controller/AppealApi.java:40` and `:78`, `imports/controller/ImportedPunishmentApi.java:48`, `cloudnet/controller/CatalogueApi.java:30`.
- Constraints: `appeal/dto/AppealSubmissionDTO.java:13-15`, `appeal/dto/AppealReviewDTO.java:25-27`, `evidence/dto/EvidenceDTO.java:50-51`, `cloudnet/dto/CatalogueDTO.java:45`, `imports/dto/ImportedPunishmentDTO.java:61-63`.
- No custom constraint-violation handler. Staff routes get the Micronaut default body (not verified; the app was not run).
- Public routes: `PublicErrorFilter` rewrites every framework 400 under `/public/**` to `{"code":"VALIDATION","availableFrom":null}`. `PublicAttachmentController.java:45` returns the same body for a missing file.
- Service-level validation returns `ErrorKind.VALIDATION` explicitly, e.g. `discord/ticket/TicketService.java:8-11` and `discord/controller/DiscordTicketController.java:54`.

### Security (401 and 403)

- `application.yml:447-477`: Micronaut security. Bearer auth with `introspection.enabled: false` (`:453-458`), JWT issuer and audience validators (`:459-466`), `intercept-url-map` (`:467-479`): `/health/**` anonymous, `/prometheus` METRICS, swagger VIEWER. Blackhole role mapping at `application.yml:71-106`.
- Roles: `security/Roles.java:10-23`. Player session role: `security/PlayerSessionRule.java:12`, prefix `bhps_` at `:15`. `security/PlayerSessionTokenValidator.java:16-32` accepts only `bhps_` tokens.
- Argument binders: `security/PlayerSessionArgumentBinder.java:27-31`, `security/ActorArgumentBinder.java:34-38`.
- No custom `AuthenticationFailedHandler` or `SecurityFilter`.
- Staff 401 and 403 (other than the groups overage): the framework default body (not verified).
- Public 401 on a missing or expired session (for example `GET /public/session`) is also the framework body. `PublicErrorFilter` only rewrites 400s. `PublicApi.java:56` and `:67` document these without a body schema.

### Special cases that must keep exact semantics

a) Uniform login error, `POST /public/session`
- Single constant: `publicapi/service/PlayerSessionService.java:39` `INVALID_BAN_ID = Error("INVALID_BAN_ID", null, UNAUTHORIZED)`, returned by `invalidBanId()` at `:42-44`.
- Unknown or unmatched Ban ID returns it at `:85-89` (metric `failure`).
- A leaked Ban ID succeeds with scope `LIMITED` (`:91-98`), not an error.
- `publicapi/controller/PublicController.java:56-60` maps through `respond()` (`:125-128`): 401 and `PublicErrorBody.of(error)`, with `Cache-Control: no-store`.
- Unreadable body or framework 400 becomes the same 401 body (`PublicErrorFilter.java:28-31`).
- Documented in `publicapi/controller/PublicApi.java:36`, `:43-44`.

b) 409 responses with `availableFrom`
- Public `NOT_ELIGIBLE` (409): `publicapi/service/PublicAppealService.java:178` `Error("NOT_ELIGIBLE", summary.availableFrom(), CONFLICT)`, reached from `:173` for NOT_YET and COOLDOWN. The ISO instant is built at `:86-87` and `:195-196`. The race path at `:127` returns `availableFrom = null`.
- Availability model: `appeal/service/AppealEligibilityService.java:104-108` (`Availability(State, availableFrom)`), computed at `:115-135`.
- Discord `NOT_YET` (409): `discord/ticket/TicketService.java:129` with `availableFrom` as epoch millis (Long). Source `discord/ticket/TicketAppeals.java:27`, `discord/ticket/PortalTicketAppeals.java:65` and `:96-105`.
- Discord `NOT_ELIGIBLE` is 404, not 409 (`TicketService.java:481`, message `:54`).
- Encoding differs: public routes use ISO strings, Discord uses epoch ms. Changing either is a breaking change for its clients.

c) Discord `TRANSCRIPT_MISMATCH` with backend tallies
- Code enum: `discord/dto/DiscordTicketDTO.java:41`.
- Branch: `discord/ticket/TicketService.java:309-313`. Reads `store.tally(id)` (`:311`), returns CONFLICT with `backendCount` and `backendLastMessageId` (`:313`).
- Tally: `discord/ticket/DiscordTicketStore.java:25` `Tally(int count, long lastMessageId, int edits, int deletes)`; SQL at `discord/ticket/JdbcDiscordTicketStore.java:465-470`.
- Wire fields: `DiscordTicketDTO.java:310-311` and `:318` (`backendCount` Integer, `backendLastMessageId` String decimal).
- Route docs: `discord/controller/DiscordTicketApi.java:147-158`.

d) `PROTOCOL_REQUIRED`
- Enum: `discord/dto/DiscordTicketDTO.java:43`. Helper `discord/ticket/TicketService.java:492-493` (CONFLICT).
- Callers: `TicketService.java:252` (archive), `:263` (delete), `:334` (not complete), `:358` (`NOT_DECIDED` or `TICKET_INCOMPLETE`).
- Related 503: `TicketService.java:338` and `:360` (UNAVAILABLE, no code).
- Docs: `DiscordTicketApi.java:82`, `:90`, `:168`, `:176`.

### Encoding and transport facts

- Errors are JSON with `errorMessage`/`kind` (internal) or `code`/`availableFrom` (public and Discord fields as above). No RFC 9457 `type`, `title`, `status`, `detail` or `instance` anywhere.
- `application/problem+json`, `ProblemDetail` and `problem+json` appear nowhere in `backend/`, `web/src`, `portal/server`, `velocity/src` or `discord/src`.

## 3. Client dependency map

Shared fields a migration must keep, by client:

| Client | Reads from error bodies | Reads from list responses | Sends `X-API-VERSION` |
|---|---|---|---|
| web (`web/src/api/http.ts`, `blackhole.ts`, `uploadEvidence.ts`, `caseEvents.ts`) | `errorMessage` (`http.ts:90`), `_embedded.errors[0].message` (`:89-93`), `message` (`:94`), `kind` if VALIDATION/NOT_FOUND/CONFLICT (`:75`, `:95`), plain-text body (`:85-87`), `uploadEvidence.ts:47-53` `errorMessage` | `content` (Page) via `pageContent` (`blackhole.ts:299-307`) and `collectPages` (`:350-360`, `MAX_PAGES` 25 at `:293`); `items` (evidence `:641`, cases `:746`, active punishments `:743`, staff and Discord links `:691`, `:698`, evidence-shares `:822`, protocol `:852`); timeline `items` + `nextCursor` (`:757-771`); bare arrays (sections `:489-493`, client rules `:514-516`, `punishment/short` `:749`) | yes, on every openapi-fetch call (`http.ts:6-7`, `:63`) |
| portal BFF (`portal/server/lib/bff.ts`) | 409 `code` (`bff.ts:142`, must be in `CONFLICT_CODES`, `portal/src/api/contract.ts:206-214`) and `availableFrom` (`:143`, ISO); 422 file routes `code` (`:122`, `FILE_REJECTION_CODES`); 409 file routes `code` (`:126`); 422 non-file ignores body (`:148-149`, always PII_REJECTED) | none; `/api/punishment`, `/api/appeal`, `/api/conversation` relayed unchanged (`bff.ts:298-300`) | no (`bff.ts:256-264`, `:341`) |
| velocity (`HttpBackendGateway.java`, generated client) | none. `getCode()` used for 404 and status (`HttpBackendGateway.java:141`, `PunishService.java:120-175`, `StaffLinkService.java:64-70`); `getMessage()` logged only; `getResponseBody()` never used | `templates()` `getContent()` (`HttpBackendGateway.java:84-86`), page 0 only, cached 30 s (`:49`); `reportFeedback` `getItems()` (`:189-190`); `BackendSectionSource.java:22` and `HttpClientRuleBackend.java:29` bare arrays | no (`BearerHttpClient.java:39, 63` retries once on 401) |
| discord (`HttpBackendGateway.java`) | `code` (`:187`), `availableFrom` (`:188`, `asLong` epoch ms), `backendCount` (`:189`), `backendLastMessageId` (`:190`) from `failed()` (`:178-199`); `TRANSCRIPT_MISMATCH` rescan loop at `TicketLifecycle.java:248-262` (`:254`); `NOT_YET` in `command/AppealCommands.java:74-75` | `tickets()` `getTickets()` (`:220-221`), `whoami` `getLinks()` (`:98`), `staffLinks()` `getLinks()` (`:354`), `outbox()` `getItems()` + `getRelayedSeq()` (`:244-246`, cursor `afterSeq`+`limit`) | no (`backend/ApiClients.java`) |

Notes:
- `backendCount` and `backendLastMessageId` are parsed but not read by production code. Only the test fake sets them (`discord/src/test/java/.../testing/FakeTicketBackend.java:340-343`).
- The web `http.ts` drops `UNAVAILABLE`, `UNAUTHORIZED`, `FORBIDDEN` and `INVALID_CODE` from body `kind` (status fallback only).
- Other web HTTP-status handling: 401 renew then sign-in, 403 forbidden, 404 mapped to empty for profile (`blackhole.ts:451`) and BanId and evidence views, 409 conflict views, 413/415/422/503 in upload and file views.
- `web/server/lib/proxy.ts:129-174` relays status and body as-is.
- Web types come from `client/specs/blackhole-api-0.0.39.yml` (`web/package.json`). The Java client uses 0.0.40. The two specs differ.

## 4. `problem-json` library facts

Verified:

- The repo does not use the module. `backend/build.gradle.kts` has no `micronaut-problem-json` or `micronaut.problem` entry. Nothing in `gradle/` or `settings.gradle.kts` defines it either. The `mn` catalog comes from `io.micronaut.platform.catalog` 5.0.2 (`settings.gradle.kts:4`).
- The local Micronaut 4.10.2 platform catalog (cached `micronaut-platform-4.10.2.toml`) pins `micronaut-problem-json = "3.9.0"`. The same catalog is what the `mn` accessors expose (`mn.micronaut.problem.json`).
- The Micronaut Problem JSON docs page (fetched at `https://micronaut-projects.github.io/micronaut-problem-json/latest/guide/`) lists coordinates `io.micronaut.problem:micronaut-problem-json` and version 5.1.0 as latest. The page does not state which Micronaut version 5.1.0 targets.
- The docs say: `ProblemErrorResponseProcessor` sets `application/problem+json` and the status from the Problem's `status`. `ThrowableProblemHandler` handles `ThrowableProblem`. Custom problems extend `AbstractThrowableProblem`. Problem fields include `type`, `title`, `status`, `detail` and `parameters`.
- Config: `problem.enabled` (default `true`), `problem.stack-trace` (default `false`).
- Since 2.2.3, `detail` is omitted for unhandled exceptions unless the root cause is `UnsatisfiedRouteException` or `ThrowableProblem`. A `ProblemJsonErrorResponseBodyProvider` subclass can override `includeErrorMessage`.
- Since Micronaut 4.0.0 the module uses `jakarta.validation`.

Not verified:

- How `HttpStatusException` renders with the module. The docs do not say. The search result hinted that the Problem handler covers only `ThrowableProblem`, which would mean an `HttpStatusException` keeps the default body. This is an inference and needs a spike.
- Whether 3.9.0 and 5.1.0 behave the same, and whether 3.9.0 is correct for Micronaut 4.10.2 (the BOM compatibility). Check the 3.9.0 release notes or resolve the artifact before choosing a version.
- Whether `application/problem+json` is parsed by openapi-fetch (web) and Java clients as JSON. Not checked.

## 5. Risks for a migration

1. Velocity runs outside the cluster and is updated separately. Old plugins will meet the new backend. Velocity reads only status and `getCode()`, so the status codes must not change. But the generated Java client (`client/`) deserializes list responses. A page wrapper change or a removed bare array breaks old plugins at `BackendSectionSource.java:22`, `HttpClientRuleBackend.java:29`, `HttpBackendGateway.java:84-86` and `:189`. Plan a compatibility window: keep old list shapes on the current versioned route and add the new shape behind `@Version("2")`, per `.claude/rules/controller-versioning.md`.
2. The generated client in `client/` must change. Its spec (`0.0.40`) is wrong about `Page` (spec:212, :2767, :3305, :3959, :4402) and about `pageable` (spec:212, :2755, :2882, :3293, :3947, :4316, :4383, :4864). Regenerating the client changes its model and API classes, so velocity and discord must be rebuilt against it.
3. The web spec (`0.0.39`) and the Java spec (`0.0.40`) already diverge. Pin both to one spec before changing any shape.
4. The portal BFF reads `code` and `availableFrom` from 409s and `code` from 422 file routes. If `code` moves to `type` (a URI), every 409 collapses to CONFLICT and every file 422 to INVALID_FILE. Keep `code` and `availableFrom` as extension members.
5. The Discord `TRANSCRIPT_MISMATCH` rescan (`TicketLifecycle.java:254`) stops if `code` moves. `NOT_YET` (`AppealCommands.java:74`) never fires if `availableFrom` moves or changes type (epoch ms). Keep both as extension members with the same types.
6. Login uniformity (`PlayerSessionService.java:39`) is a security property. Mapping it to RFC 9457 must keep one body for every failure, with no Ban ID, no hint of existence, and the same metric tag. `PublicErrorFilter` and `PublicApi` documentation must change together.
7. Web `pageContent` (`blackhole.ts:299-307`) and the `items` readers fail silently when a wrapper changes: an unexpected shape gives an empty list. Templates, reports, appeals and elo history stop paging after the first page (`collectPages` stops early). Add a contract test for each shape before changing it.
8. Velocity reads only page 0 of `/template` (`HttpBackendGateway.java:84-86`), so the current behaviour already loses templates beyond the default page size. A pagination change must expose this or fix it, not hide it.
9. The `framework` `Pageable` endpoints have no total and an unrelated default page size (100). Changing the default to a smaller value changes what old clients see on the unpaginated call. Decide the defaults once.
10. Seven unpaginated endpoints (for example `/discord/staff-links`, `/client-rule`, `/punishment/short/{shortId}`) need caps. Caps change results for existing callers. Check each consumer before capping.
11. The framework default body for 400, 401, 403 and `HttpStatusException` is unverified. A spike against the running backend (per the project's verification rule) should record the actual bodies before the design fixes them.
12. The `application/problem+json` switch is a content-type change. Clients that check `application/json` (web `http.ts`, portal `bff.ts` `parseJson`) must accept the new type. `parseJson` returns undefined for non-JSON, which the BFF turns into 503 (`bff.ts:157-163`, `:299`), so a missing content-type match would turn errors into outages.
13. Documented-but-not-spec routes: `POST /admin/import/vanilla` is absent from the spec (code only). Add it to the spec or leave it out of the client, deliberately.
14. Plain-string and empty bodies (section 2, bare and empty bodies) have no JSON shape. Each one needs a decision: convert to problem details, or document as an exception.
15. Dependency version: the project is on Micronaut 4.10.2. The problem-json module pinned by that platform is 3.9.0, while the docs are at 5.1.0. The openspec context claims Micronaut 5. Settle the version before the proposal, or the module will not match the framework.
