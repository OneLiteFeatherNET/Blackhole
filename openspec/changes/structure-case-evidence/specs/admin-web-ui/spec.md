# Spec Delta

## Purpose
Adds the case page sections, the evidence picker entry point and the canned reasons page to the staff panel.

## ADDED Requirements

### Requirement: Case page sections and evidence picker
The staff case page SHALL render the sections defined by the case-structure capability in their order, and SHALL offer "Present a case file" in the Evidence section to moderators with an open appeal. The picker MUST show the shareability badge and lock reason of every row and MUST NOT present a case file until the preview has been shown and confirmed.

#### Scenario: Picker entry disabled
- **WHEN** the case has no open appeal
- **THEN** "Present a case file" is disabled and states the reason

### Requirement: Canned reasons page
The Administration workspace SHALL contain a Canned reasons page at `/admin/canned-reasons` in the Configuration group, with one filter per kind, an edit dialog that shows a Markdown preview and a character count, and a deactivate action that is disabled for system reasons with its reason stated. The page is reachable by administrators only.

#### Scenario: System reason
- **WHEN** an administrator opens the system reason `BAN_ID_LEAKED`
- **THEN** the deactivate action is disabled and the page states that the reason is required by the leak close
