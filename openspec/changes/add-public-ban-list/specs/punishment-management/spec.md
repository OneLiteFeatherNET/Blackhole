## ADDED Requirements

### Requirement: Template public reason

A punishment template SHALL carry an optional `publicReason`: plain text of 1 to 200 characters, or absent. An absent or blank `publicReason` SHALL mean that punishments created from the template are not listed on the ban list. The `publicReason` SHALL be separate from the staff `reason` and SHALL NOT be derived from it. Only the ADMIN role SHALL set or change it.

#### Scenario: Template with a public reason

- **WHEN** a template is created with `publicReason` "Use of unfair client modifications"
- **THEN** the template returns that text and the staff `reason` is unchanged

#### Scenario: Template without a public reason

- **WHEN** a template is created without `publicReason`
- **THEN** punishments created from it are not listed

#### Scenario: Public reason longer than the limit

- **WHEN** a template is saved with a `publicReason` of 201 characters
- **THEN** the save is refused with a validation error

### Requirement: Public fields are snapshotted on apply

Applying a template SHALL copy its `publicReason` and the kind of the punishment's section onto the new punishment as `public_reason` and `public_kind`, and SHALL compute its entry hash. An amendment that changes the expiry SHALL update `public_expires_at`. A revocation SHALL set `public_revoked_at`. None of these changes SHALL rewrite a reason that was already copied.

#### Scenario: Apply copies the snapshot

- **WHEN** a template with a public reason is applied
- **THEN** the punishment stores that reason and its section kind, and a hash is computed once

#### Scenario: Revocation sets the revoke instant

- **WHEN** a punishment is revoked
- **THEN** its `public_revoked_at` is set to the revocation instant, and its `public_reason` is unchanged

#### Scenario: Expiry amendment

- **WHEN** an amendment changes the expiry of a listed punishment
- **THEN** `public_expires_at` is updated to the new expiry and the entry shows the new expiry day

### Requirement: Kick punishments are never listed

A punishment of kind `KICK` SHALL NOT receive public fields, a hash or an entry, even when its template has a public reason.

#### Scenario: Kick with a public template

- **WHEN** a kick step of a template with a public reason is applied
- **THEN** the kick has no `public_hash` and no entry is listed for it
