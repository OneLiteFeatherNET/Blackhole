# Spec Delta

## Purpose

Imported punishments that have no template are linked to one afterwards.

## ADDED Requirements

### Requirement: List imported punishments without a template
The system SHALL let an `ADMIN` read `GET /admin/import/punishments`, answering the punishments imported from a vanilla ban list that are linked to no template, grouped by their original reason (`importedReason`, else the stored `reason`, else `Imported vanilla ban`), the largest group first, with the total.

#### Scenario: Grouped by reason
- **WHEN** three imported punishments without a template exist, two with the reason `Hacking` and one with `Spam`
- **THEN** the groups are `Hacking` with count 2, then `Spam` with count 1, and the total is 3

#### Scenario: Not an admin
- **WHEN** a moderator reads the list
- **THEN** the response is HTTP 403

### Requirement: Link imported punishments to a template
The system SHALL let an `ADMIN` link imported punishments that have no template to a template via `POST /admin/import/punishments/retemplate`, selecting either every one with an original reason (`reason`) or an explicit list (`identifiers`, at most 500); exactly one of the two is required. The template MUST exist, MUST NOT be in a `MUTE` section and MUST be in an enabled section. Only the template changes: the system MUST NOT change any score, write an ELO event, or set an escalation group, step or cost, and MUST keep the original reason in the metadata as `importedReason`. A punishment that already has a template MUST NOT be overwritten, one that already has the target template is counted as unchanged, and a punishment that is not an import, does not exist or lies in another section than the template is counted as skipped, so repeating a request changes nothing. Each change SHALL evict the cached profile and, for an active punishment, publish `punishment.updated` so the proxy mirror carries the template's reason.

#### Scenario: Link by reason
- **WHEN** an admin links the reason `Hacking` to a ban template
- **THEN** every imported punishment without a template and with that reason has the template and keeps `importedReason`
- **AND** no score changed

#### Scenario: Repeat is a no-op
- **WHEN** the same request is sent a second time
- **THEN** nothing is linked again and no second case event is recorded

#### Scenario: Never overwrite
- **WHEN** a selected punishment already has another template
- **THEN** it is counted as skipped and keeps that template

#### Scenario: Mute template
- **WHEN** the template is in a mute section
- **THEN** the response is HTTP 400 and nothing changes

### Requirement: Re-templating is recorded on the case
Linking an imported punishment to a template SHALL record `punishment.retemplated` on its case after the write, with the template id, the verified actor of the token and the dedupe key `retemplated:<punishment>:<template>`. A recording failure MUST NOT fail the link.

#### Scenario: Actor from the token
- **WHEN** an admin links a punishment
- **THEN** its case has a `punishment.retemplated` event with that admin as verified actor
