# Interface Contract: Punishment REST API

Two controllers, both versioned via Micronaut's built-in scheme
(`@Version(ApiVersion.V1)` — resolved from `X-API-VERSION` header or `api-version`/`v`
query param, default version `1`; paths carry no `/v1` prefix, per
`.claude/rules/controller-versioning.md`). No authentication on either — open per
constitution Principle II. A third, related controller (`ProfileController`, mounted at
`/profile`) owns the `PunishmentProfileEntity` CRUD surface and is documented in this
feature's `data-model.md`/`research.md` as the entity this API's responses embed, but
its endpoints belong to the sibling `profile` feature package, not this contract.

## `PunishmentEntityController` — mounted at `/punishment`

### `POST /punishment/active/{owner}/{templateId}/{source}`

Applies a punishment template to a player, creating the profile if this is their first
ever punishment (spec US1, FR-001, FR-012).

**Path variables**:

| Name | Type | Validation |
|---|---|---|
| `owner` | `String` | `@Pattern(regexp = "^[a-fA-F0-9]{128}$")` — SHA-512 hash |
| `templateId` | `UUID` | none beyond UUID parseability |
| `source` | `UUID` | none — unverified caller-supplied actor id (constitution Principle II) |

**Response 200** (`PunishProfileResponse` — actually a `PunishProfileDTO`):

```json
{
  "owner": "<128-hex-char hash>",
  "activeChatBan": null,
  "activeBan": {
    "identifier": "kX9f...-22char",
    "source": "00000000-0000-0000-0000-000000000000",
    "type": "NETWORK",
    "scope": null,
    "template": { "identifier": "...", "reason": "Cheating", "type": "NETWORK", "eloDelta": 0, "metaData": {} },
    "metaData": { "creationDate": 1768867200000, "updateDate": 1768867200000, "expirationDate": 1768953600000 }
  },
  "history": [],
  "metaData": {}
}
```

**Response 404**: no body — the template referenced by `templateId` does not exist
(FR-012: "reject it clearly rather than creating an incomplete or ambiguous
punishment").

**Response 400**: standard Micronaut validation failure body — `owner` failed the
SHA-512 pattern.

**Side effects** (not visible in the response body): a `PunishmentTemplateEntity`
with a nonzero `eloDelta` triggers an ELO delta on the corresponding track unless
`source` is the ELO engine's own system UUID (see `research.md`); a `punishment.created`
domain event is published on `blackhole.events`, mirrored into Redis for proxy
consumption within a few seconds (spec SC-001); a `profile.created` event is published
additionally when this was the player's first-ever punishment.

### `POST /punishment/active/{owner}/ban/revoke/{source}`

Revokes a player's active SERVER/NETWORK punishment, moving it into history (spec US4,
FR-008).

**Path variables**: `owner` (same SHA-512 pattern), `source` — the caller revoking it,
unverified.

**Response 200**: same `PunishProfileResponse` shape as apply, with `activeBan` now
`null` and the revoked punishment appended to `history` (its `metaData.revokedBy` set
to `source`).

**Response 404**: no body — either no profile exists for `owner`, or the profile has
no active ban to revoke (FR-013: "report that clearly rather than silently succeeding
or creating a punishment" — modeled here as 404, not a distinguishable "no profile" vs.
"no active punishment" response). Per spec Edge Cases, a ban that expired moments
earlier is treated identically to "no active ban" — both fall through to this same 404,
since by the time the request is handled the slot is already `null`.

### `POST /punishment/active/{owner}/mute/revoke/{source}`

Identical contract to the ban-revoke endpoint above, operating on `activeChatBan`
instead of `activeBan`.

### `GET /punishment`

Paginated listing of every punishment ever created, active or not. Accepts
Micronaut's standard `Pageable` query params (`page`, `size`, `sort`).

**Response 200** (`Page<PunishEntryDTO>`):

```json
{
  "content": [
    {
      "identifier": "kX9f...-22char",
      "source": "00000000-0000-0000-0000-000000000000",
      "type": "NETWORK",
      "scope": null,
      "template": { "identifier": "...", "reason": "Cheating", "type": "NETWORK", "eloDelta": 0, "metaData": {} },
      "metaData": { "creationDate": 1768867200000, "updateDate": 1768867200000 }
    }
  ],
  "pageable": { "number": 0, "size": 20 },
  "totalSize": 1
}
```

No filter by `owner`/`type`/active-status exists on this endpoint — it is a flat,
unfiltered listing of the `punishments` table.

## `PunishmentTemplateController` — mounted at `/template`

Every endpoint here injects `PunishmentTemplateRepository` directly with no service
layer in between — see plan.md's Constitution Check (Principle III) for why this is
flagged, not treated as the intended shape.

### `POST /template/`

Creates a new template (spec US5, FR-010).

**Request body** (`PunishTemplateRequestDTO`):

```json
{
  "metaData": { "duration": "PT1H" },
  "reason": "Spamming",
  "type": "CHAT",
  "eloDelta": -20,
  "identifier": null
}
```

`identifier` **must** be `null` — this is the create/update discriminator (the same
nullable-identifier convention flagged as inconsistent with `micronaut-dto-contract`
in plan.md, which calls for separate `CreateRequest`/`UpdateRequest` types instead of
one nullable field).

**Response 200** (`PunishTemplateDTO`): the saved template, `identifier` now populated.

**Response 405**: `identifier` was non-null on a create call.

**Response 400**: standard validation failure (missing `reason`/`type`/`metaData`).

### `POST /template/update`

Updates an existing template (spec US5 Acceptance Scenario 2 — future applications use
the new values, already-applied punishments are unaffected since `PunishmentEntity`
snapshots `type` at creation time).

**Request body**: same `PunishTemplateRequestDTO` shape, `identifier` **required**.

**Response 200** (`PunishTemplateDTO`): the updated template.

**Response 400**: `identifier` was `null`.

**Response 404**: no template exists with that `identifier`.

### `DELETE /template/delete/{identifier}`

Removes a template (spec US5 Acceptance Scenario 3 — already-created punishments are
unaffected since they hold their own snapshot, not a live reference requiring the
template to still exist... **caveat**: `PunishmentEntity.template` is a live
`@ManyToOne` reference with a DB foreign key to `punishment_templates.identifier` —
deleting a template that still has `PunishmentEntity` rows pointing at it will fail at
the database level (no `ON DELETE` cascade/set-null configured in the changelog) rather
than the clean "already-applied punishments unaffected" the spec describes; the
controller itself performs no check for this before calling `delete`).

**Response 200** (`PunishTemplateDTO`): the deleted template's last-known values.

**Response 404**: no template exists with that `identifier`.

### `GET /template/`

Paginated listing of all templates. Accepts standard `Pageable` query params.

**Response 200** (`Page<PunishTemplateDTO>`): same shape as the create response, one
entry per template.

### `GET /template/{identifier}`

Fetches a single template by id.

**Response 200** (`PunishTemplateDTO`).

**Response 404**: no body — no template exists with that `identifier`.

## Not part of this contract

- Punishment-profile CRUD (`POST /profile/`, `POST /profile/update/{owner}`,
  `DELETE /profile/delete/{owner}`, `GET /profile/`, `GET /profile/{owner}`,
  `POST /profile/{owner}/session`) belongs to the `profile` feature package's own
  controller and is out of scope here, even though this contract's responses embed
  `PunishProfileDTO`/`PunishProfileResponse` shapes owned by that package.
- The `blackhole.punishment.sync` Redis pub/sub channel and the
  `blackhole:punish:ban:{owner}`/`blackhole:punish:chatban:{owner}` keys are an
  internal side channel to Velocity proxies, not a public REST contract — see
  `research.md`'s Redis-propagation decision and `RedisTopology`/`PunishmentSyncMessage`.
- Evidence attachment (`PunishmentEvidenceEntity`/`PunishmentEvidenceRepository`) has
  no dedicated REST endpoint in this package today — evidence rows are created by
  other features (e.g. the ELO engine's `ChatToxicityService`) that hold a direct
  repository/entity reference, not through a `/punishment` HTTP call.
