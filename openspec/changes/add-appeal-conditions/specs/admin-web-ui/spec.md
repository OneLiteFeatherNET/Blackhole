# Spec Delta

## Purpose
Adds the appeal conditions editor to the admin area.

## ADDED Requirements

### Requirement: Appeal conditions page
The admin UI SHALL have an "Appeal conditions" page in the navigation rail that shows the current version number and text and the list of earlier versions to every team role. Only `ADMIN` MUST see an editor with a character counter (limit 4000) and a "Save as new version" action; saving MUST never overwrite a version. The strings exist in German and English.

#### Scenario: Viewer
- **WHEN** a viewer opens the page
- **THEN** the current text and the version list are shown and no editor or save action is rendered

#### Scenario: Admin saves
- **WHEN** an administrator edits the text and saves
- **THEN** a new version becomes current and appears at the top of the version list

#### Scenario: Too long
- **WHEN** the text exceeds 4000 characters
- **THEN** the save action is disabled and the counter shows the overflow

#### Scenario: Concurrent save
- **WHEN** the backend answers HTTP 409 on save
- **THEN** the page reloads the current version and tells the administrator to review and save again
