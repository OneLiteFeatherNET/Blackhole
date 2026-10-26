# Spec Delta

## Purpose
Gives the team predefined texts for closing a case, deciding an appeal, replying in chat and opening a case file. Admins
maintain them, moderators pick them, every use keeps the text it had, and the texts can be translated through the i18n platform.

## ADDED Requirements

### Requirement: Four kinds of predefined reason
A canned reason SHALL have exactly one kind: `CLOSING`, `DECISION`, `REPLY` or `ALLEGATION`, a stable code of the form `^[A-Z][A-Z0-9_]{2,63}$` that is unique per kind and never changes after creation, a body of 1 to 4000 characters in Markdown, a `system` flag, an `active` flag and a sort order.

#### Scenario: Code is unique per kind
- **WHEN** an admin creates `CLOSING / LEAK_CLOSE` and the same code already exists for `CLOSING`
- **THEN** the answer is HTTP 409 `CODE_TAKEN` and nothing is stored

#### Scenario: Same code in another kind
- **WHEN** an admin creates `DECISION / LEAK_CLOSE`
- **THEN** the reason is stored

### Requirement: Canned texts pass the same rules as messages
A body SHALL be rejected (HTTP 422 `PII_REJECTED`) when `PiiGuard` or `MessageRules` refuse it, including a UUID, an IP address, a Ban ID lookalike or a link target that is not http(s). A body MUST NOT contain placeholders or variables in this version.

#### Scenario: Ban ID in a body
- **WHEN** an admin saves a body that contains a Ban ID lookalike
- **THEN** the answer is HTTP 422 `PII_REJECTED` and the stored body is unchanged

### Requirement: Only administrators manage canned reasons
Creating, editing and deactivating canned reasons SHALL require `ADMIN`. Moderators SHALL read the active reasons of every kind for their pickers. Inactive reasons MUST NOT be returned to a moderator. Every change MUST record the time and the `oid` of the administrator, and the audit line MUST NOT contain the body.

#### Scenario: Moderator reads active reasons
- **WHEN** a moderator requests the `REPLY` reasons
- **THEN** only active `REPLY` reasons are returned

#### Scenario: Moderator writes
- **WHEN** a moderator tries to create a reason
- **THEN** the answer is HTTP 403

### Requirement: System reasons cannot be deactivated
A reason with `system` set SHALL NOT be deactivated or deleted (HTTP 409 `SYSTEM_REASON`), but its body MAY be edited. The `CLOSING` reason `BAN_ID_LEAKED` is a system reason. Deletion of any reason is not offered: a reason is deactivated, so every use stays readable.

#### Scenario: Deactivate the leak closing reason
- **WHEN** an admin deactivates `CLOSING / BAN_ID_LEAKED`
- **THEN** the answer is HTTP 409 `SYSTEM_REASON` and the reason stays active

### Requirement: Uses keep the text they had
A use of a canned reason (a chat message, a decision message, a closing comment, a case file opener) SHALL store its text at the time of use, together with the reason code. A later edit of the body MUST NOT change any stored use.

#### Scenario: Edit after use
- **WHEN** an admin edits the body of a reason that a message used
- **THEN** the message text is unchanged and the reason's body is the new text

### Requirement: Translation through the i18n catalog
Canned texts SHALL be translated through the catalog of `add-i18n-platform` (PR #388), under the keys `canned.<kind>.<id>`. The English body is the source text and the fallback: when the catalog has no value for the viewer's locale, or the catalog is not available, the English body is returned. This capability MUST NOT store translations itself.

#### Scenario: No catalog value
- **WHEN** a closing comment is rendered for a locale that has no catalog value for `canned.CLOSING.<id>`
- **THEN** the English body is used

#### Scenario: Catalog value exists
- **WHEN** the catalog has a value for the viewer's locale
- **THEN** that value is used

### Requirement: Reasons in chat replies
A `REPLY` canned reason SHALL be inserted into the staff composer as text that the person can edit before sending. It MUST NOT be sent automatically. A sent message SHALL record `cannedReasonCode` in its payload when the text came from a reason.

#### Scenario: Insert and edit
- **WHEN** a moderator inserts a `REPLY` reason and changes one word before sending
- **THEN** the sent message contains the edited text and `cannedReasonCode` set to the reason's code
