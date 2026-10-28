## MODIFIED Requirements

### Requirement: List imported punishments without a template
The system SHALL let an `ADMIN` read `GET /admin/import/punishments`, answering the punishments imported from a ban list (vanilla or a plugin import, identified by `metaData.importSource`) that are linked to no template, grouped by their original reason (`importedReason`, else the stored `reason`, else the source's default reason such as `Imported vanilla ban`), the largest group first, with the total. Kick punishments MUST NOT be listed.

#### Scenario: Grouped by reason
- **WHEN** three imported punishments without a template exist, two with the reason `Hacking` and one with `Spam`, one from LiteBans and two from vanilla
- **THEN** the groups are `Hacking` with count 2, then `Spam` with count 1, and the total is 3

#### Scenario: Kicks are not listed
- **WHEN** a plugin import contains a kick without a template
- **THEN** the kick is not in the answer

#### Scenario: Not an admin
- **WHEN** a moderator reads the list
- **THEN** the response is HTTP 403

### Requirement: Link imported punishments to a template
The system SHALL let an `ADMIN` link imported punishments that have no template to a template via `POST /admin/import/punishments/retemplate`, selecting those with an original reason (`reason`) or a list (`identifiers`, at most 500), exactly one of the two. The template MUST exist and be in an enabled section. A ban-type import MUST NOT be linked to a template in a mute section, and a mute-type import MUST be linked to a template in a mute section. Only the template changes: no score, ELO event, escalation field or duration; the original reason is kept as `importedReason`.

#### Scenario: Link by reason
- **WHEN** an admin links the reason `Hacking` to a ban template
- **THEN** every imported punishment without a template and with that reason has the template and keeps `importedReason`
- **AND** no score changed

#### Scenario: Mute import to a mute template
- **WHEN** an admin links the reason `Spam` of mute imports to a mute template
- **THEN** those mute imports take the template and keep their section

#### Scenario: Mute template
- **WHEN** a ban-type import is linked to a template in a mute section
- **THEN** the response is HTTP 400 and nothing changes

### Requirement: Re-templating is idempotent and announced
A punishment that already has a template MUST NOT be overwritten, one that already has the target template is counted as unchanged, and a punishment that is not an import, does not exist, is a kick or lies in another section than the template is counted as skipped, so repeating a request changes nothing. Each change SHALL evict the cached profile and, for an active punishment, publish `punishment.updated` so the proxy mirror carries the template's reason.

#### Scenario: Repeat is a no-op
- **WHEN** the same request is sent a second time
- **THEN** nothing is linked again and no second case event is recorded

#### Scenario: Never overwrite
- **WHEN** a selected punishment already has another template
- **THEN** it is counted as skipped and keeps that template
