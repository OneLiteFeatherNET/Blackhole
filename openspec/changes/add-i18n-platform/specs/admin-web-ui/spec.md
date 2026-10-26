# Spec Delta

## Purpose
Adds the translation editor of the catalog to the Administration workspace of the panel.

## ADDED Requirements

### Requirement: Translation editor

The panel SHALL offer a Translation editor in the Administration workspace that lists the catalog keys, marks missing translations and parity violations, edits one key and locale at a time, and shows the Problem Details message of a rejected save next to the field.

#### Scenario: Missing translation is marked
- **WHEN** an administrator opens the editor and a key has no `de` text
- **THEN** the row shows a missing-translation marker for `de`

#### Scenario: Rejected save
- **WHEN** an administrator saves a text with an unclosed tag
- **THEN** the editor keeps the typed text and shows the validation message next to the field

### Requirement: Fallback preview
The editor SHALL show, for the selected key and locale, a preview of what a player with that locale sees. The preview MUST use the same fallback chain as the proxy (exact locale, language, `en`), MUST show which locale the preview text came from, and MUST render the MiniMessage output with example arguments that are marked as examples.

#### Scenario: Preview falls back
- **WHEN** an administrator previews `de` for a key that has no `de` text
- **THEN** the preview shows the `en` text and labels it as the `en` fallback

#### Scenario: Preview escapes examples
- **WHEN** the example argument contains markup
- **THEN** the preview shows the markup as text

### Requirement: Version history per key
The editor SHALL show the history of a key and locale, every version with its author and time, and MUST NOT offer any way to edit or delete an earlier version.

#### Scenario: History is read only
- **WHEN** an administrator opens the history of a key
- **THEN** every version is listed and no edit control exists for an earlier version
