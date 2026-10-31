# Spec Delta

## Purpose

Defines bulk editing of cases: one route that applies a shared metadata change (status, assignee,
priority, tags to add or remove) to a bounded list of cases, with the same rules per case as the single
edit, a result for every case, one timeline event and one audit entry per changed case, and no event
when nothing changes. Punishments themselves are not changed by this capability.

## ADDED Requirements

### Requirement: Bulk route with a bounded case list
The system SHALL offer `POST /case/bulk` (API version 1, `MODERATOR`) taking a list of at most 100 cases, each with its full punishment identifier and its `expectedVersion`, and one change. A request with zero cases, more than 100 cases, a duplicate identifier, no change field, or a malformed tag is HTTP 400 and changes nothing. The route MUST NOT accept `onBehalfOf` and MUST NOT be granted to the `DISCORD` role.

#### Scenario: Too many cases
- **WHEN** a request lists 101 cases
- **THEN** the response is HTTP 400 and no case is changed

#### Scenario: Duplicate case
- **WHEN** the same identifier is listed twice
- **THEN** the response is HTTP 400 and no case is changed

#### Scenario: No change
- **WHEN** the request sets no status, assignee, priority, tag to add or tag to remove
- **THEN** the response is HTTP 400 and no case is changed

#### Scenario: Discord bot is refused
- **WHEN** the Discord bot calls the route
- **THEN** the response is HTTP 403

#### Scenario: Viewer is refused
- **WHEN** a viewer calls the route
- **THEN** the response is HTTP 403 and no case is changed

### Requirement: Same rules per case as the single edit
Each case in a bulk edit MUST be checked with the rules of the single edit: the case's `expectedVersion` MUST match its current version, the assignee MUST be a known human staff member, and the tags after the change MUST be valid slugs within the tag maximum. The validation of the shared change (assignee, tags, status and priority values) MUST happen once before any case is written, and an invalid shared change is HTTP 422 or 400 with no case changed. A version mismatch is decided per case.

#### Scenario: Stale version on one case
- **WHEN** one of three cases has a version that is no longer current
- **THEN** that case is reported as `CONFLICT` with its `currentVersion`, and the other two are changed

#### Scenario: Unknown assignee
- **WHEN** the assignee is not a known human staff member
- **THEN** the response is HTTP 422 and no case is changed

#### Scenario: Tags exceed the maximum
- **WHEN** adding tags would give a case more tags than the maximum
- **THEN** that case is reported as `FAILED` with a message, and the other cases are still changed

### Requirement: Per-case results
The response SHALL be HTTP 200 with a result for every listed case, in request order, carrying `punishmentIdentifier`, `outcome` (`UPDATED`, `UNCHANGED`, `CONFLICT`, `NOT_FOUND` or `FAILED`), `changed`, `version`, `currentVersion` where relevant, and the counts `updated`, `unchanged`, `conflicts`, `notFound` and `failed`.

#### Scenario: Every case has a result
- **WHEN** a batch of five cases is answered
- **THEN** the response holds five items in request order, and each count equals the number of items with that outcome

### Requirement: Failure of one case is isolated
A failure or a conflict of one case MUST NOT stop the other cases of the batch from being attempted. The batch is not one database transaction: each case is written by one statement that changes its state and writes its event together, so a case is either fully changed or not changed.

#### Scenario: Stale version does not stop the batch
- **WHEN** the second of three cases has a stale version
- **THEN** the second is `CONFLICT` and the third is still written

#### Scenario: Partial failure is reported
- **WHEN** a batch of ten cases has two conflicts and one not found
- **THEN** the response lists all ten results, and each count equals the number of items with that outcome

#### Scenario: Not atomic across cases
- **WHEN** the write of the fourth case fails with a storage error after cases one to three were written
- **THEN** cases one to three remain changed, the fourth is `FAILED` with `changed` false, and the rest are attempted

#### Scenario: Retry is safe
- **WHEN** the same request is sent again after a timeout
- **THEN** the cases already changed answer `CONFLICT` with their current version and no event is appended twice

### Requirement: One timeline event per changed case
A case that changes SHALL get exactly one timeline event of type `case.bulk_edited`, payload version 1, carrying the `batchId` and the list of changed fields with their old and new values. Assignee values MUST be oids, never names. The event and the state change MUST be written by the same statement. A case that does not change MUST NOT get an event.

#### Scenario: Two fields changed
- **WHEN** a case's status and tags change in one batch
- **THEN** the case gets one `case.bulk_edited` event listing `STATUS` and `TAGS` with their old and new values

#### Scenario: Nothing changed
- **WHEN** the status of a case is already the requested status and no other field changes
- **THEN** the outcome is `UNCHANGED`, no event is appended and no audit entry is written

#### Scenario: Event carries no text
- **WHEN** a case is changed in bulk
- **THEN** the event payload holds ids, enums, slugs and oids only, and no note text

### Requirement: One audit entry per changed case
Each changed case SHALL get one staff audit entry with action `case.bulk_update`, recorded for the moderator who made the request, with the case's short id as its target. An unchanged case MUST NOT get an audit entry. If the audit write fails after the case changed, the result reports `changed` true and `auditWritten` false, the failure is logged and counted, and the batch continues.

#### Scenario: Audit per changed case
- **WHEN** a batch changes three of four cases
- **THEN** three audit entries with action `case.bulk_update` exist, one per changed case

#### Scenario: Audit fails after the change
- **WHEN** the audit write fails for one changed case
- **THEN** that case is reported with `changed` true and `auditWritten` false, and the other cases are not affected

### Requirement: Domain events per field
Each changed field of each case SHALL publish its existing domain event (`case.status_changed`, `case.assignee_changed`, `case.priority_changed` or `case.tags_changed`) after the write, with the `batchId` in its change map. A failure to publish MUST NOT fail the batch. The domain events MUST carry no note text.

#### Scenario: Status changed in bulk
- **WHEN** a case's status is changed by a batch
- **THEN** a `case.status_changed` event is published with the case identifier and the `batchId`

### Requirement: Bulk edit does not change punishments
A bulk edit SHALL change only the case row, its events and its audit entries. It MUST NOT lift, revoke, extend or otherwise change a punishment, its appeal or its evidence.

#### Scenario: Punishment untouched
- **WHEN** a case of an active ban is set to `CLOSED` in bulk
- **THEN** the punishment is still active and no punishment event is written

### Requirement: Bulk selection in the Cases page
The Cases page SHALL let a moderator select cases per row and for the page in the flat view, and select all loaded cases of one player in the grouped view. Selection SHALL be capped at 100 cases and cleared when the page, the filters or the view changes.

#### Scenario: Cap
- **WHEN** a moderator selects the 101st case
- **THEN** the selection stays at 100 and the page says so

#### Scenario: Selection cleared on filter change
- **WHEN** the moderator changes a filter
- **THEN** the selection is empty

### Requirement: Bulk panel in the Cases page
The bulk panel SHALL offer status, assignee, priority, tags to add and tags to remove, and SHALL be disabled until one change is set. A player with more than 100 cases SHALL be sent as several batches, one after another, and the result of each batch SHALL be shown.

#### Scenario: Group larger than one batch
- **WHEN** a player has 150 cases and all are selected for a status change
- **THEN** the page sends two batches, of 100 and 50 cases, and shows the outcome of each case

#### Scenario: Result list
- **WHEN** a batch returns a conflict for one case
- **THEN** the result list shows that case as a conflict with a reload action, and the list is reloaded

### Requirement: Accessible bulk interaction
The bulk panel and the result list SHALL be operable by keyboard, announce the number of selected cases and the result counts to assistive technology, and offer German and English strings with the same keys.

#### Scenario: Result announced
- **WHEN** a batch finishes
- **THEN** the counts of updated, unchanged, conflicts, not found and failed cases are announced in a live region

#### Scenario: Strings in both languages
- **WHEN** the German and English strings are compared
- **THEN** every key of the bulk panel exists in both languages
