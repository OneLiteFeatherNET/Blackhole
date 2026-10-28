## ADDED Requirements

### Requirement: Staff rollback page

The UI SHALL offer a staff rollback page under the team administration (the staff accounts tab), visible only to users with the `ADMIN` role. The page SHALL take the staff actor or the unlinked in-game account and a time window, show the preview counts per kind and per section, and then ask for the justification before it starts the job. It SHALL show the job's progress until the job is completed or cancelled. All texts MUST exist in German and English.

#### Scenario: Preview before confirm

- **WHEN** an admin chooses a staff actor and a window
- **THEN** the page shows the preview counts and does not start anything until the admin confirms with a justification

#### Scenario: Moderator does not see the page

- **WHEN** a moderator opens the team administration
- **THEN** the rollback page is not offered in the navigation

#### Scenario: Progress

- **WHEN** a job is running
- **THEN** the page shows how many items are revoked, skipped and pending, and stops polling when the job ends
