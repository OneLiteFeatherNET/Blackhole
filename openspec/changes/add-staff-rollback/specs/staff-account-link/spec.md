## ADDED Requirements

### Requirement: Link removal by a rollback

A link removed by a staff rollback with link suspension SHALL be removed with the reason `ROLLBACK`, publish `staff-link.removed` with that reason, and leave every stored `actor_oid` unchanged. The removal MUST NOT disable the staff member's identity account, and the person MUST be able to link again with a new code.

#### Scenario: Rollback removes the link

- **WHEN** a rollback with link suspension removes the link of a staff member
- **THEN** `GET /staff-link/me` for that person reports not linked, `staff-link.removed` carries reason `ROLLBACK`, and earlier punishments keep their `actor_oid`

#### Scenario: Relink after a rollback

- **WHEN** the person confirms a new code after the rollback
- **THEN** a new link is created and earlier records are not rewritten
