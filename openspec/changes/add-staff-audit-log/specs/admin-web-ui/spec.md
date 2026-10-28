## ADDED Requirements

### Requirement: Audit view for administrators

The admin area SHALL offer a read-only Audit tab (`/admin/audit`) to ADMIN users only. It SHALL list the audit entries newest first with actor, surface, action, target and time, link each target to its case or player page, and load further pages with the cursor. It SHALL offer no edit, delete or re-post control. A MODERATOR SHALL NOT see the tab.

#### Scenario: Moderator opens the admin area

- **WHEN** a MODERATOR opens the admin area
- **THEN** the Audit tab is not shown

#### Scenario: Admin follows a target

- **WHEN** an ADMIN selects an entry whose target is a case
- **THEN** the case page opens

#### Scenario: Next page

- **WHEN** an ADMIN scrolls to the end of the list
- **THEN** the next page is requested with the cursor of the last entry shown and appended once
