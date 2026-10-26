# Spec Delta

## Purpose
Makes the content texts of Blackhole translatable: templates, escalation step reasons, sections, client-rule deny messages, kick reasons, appeal conditions, canned comments and system texts. One store in the backend, exported to the proxy, edited in the administration workspace.

## ADDED Requirements

### Requirement: Catalog keys and locales
The system SHALL store every translatable content text as a catalog entry identified by a key and a locale. Keys MUST follow the typed patterns `template.<key>.name`, `template.<key>.step.<stepId>.reason`, `section.<key>.name`, `clientrule.deny.<id>`, `kick.reason.<id>`, `appeal.conditions`, `canned.<kind>.<id>` and `system.<id>`. The supported locales are `en` and `de`. `en` MUST exist for every key; `en` is the default locale and the fallback of every other locale.

#### Scenario: Key without English is rejected
- **WHEN** a caller saves a `de` text for a key that has no `en` text
- **THEN** the system rejects the save with a Problem Details error that names the key and the missing locale

#### Scenario: Unsupported locale
- **WHEN** a caller saves a text for locale `fr`
- **THEN** the system rejects the save with a Problem Details error and stores nothing

### Requirement: Append-only versions with a global sequence
The system SHALL store every save as a new row with the next version number for that key and locale and a global sequence number that is strictly increasing across all rows. A stored row MUST NOT be changed or deleted. The current text of a key and locale is the row with the highest version.

#### Scenario: Save creates a version
- **WHEN** a text is saved for a key and locale that already has version 3
- **THEN** a version 4 row is stored, version 3 stays unchanged, and the new row has a higher sequence number than every existing row

#### Scenario: Concurrent saves
- **WHEN** two saves of the same key and locale race
- **THEN** exactly one succeeds and the other is rejected with a conflict Problem Details error, and no version number is used twice

### Requirement: Validation on save

The system SHALL validate every text before it is stored: it MUST parse as MiniMessage, use only placeholders declared for its key, stay within the length limit of its key kind, and use a supported locale.

#### Scenario: Syntax error
- **WHEN** a text contains an unclosed tag such as `<red>Banned`
- **THEN** the save is rejected with a Problem Details error that names the position of the problem

#### Scenario: Placeholder set differs from the default
- **WHEN** the `de` text of `punishment.screen.reason` uses `<arg:1>` and the key declares one argument
- **THEN** the save is rejected with a Problem Details error that names the undeclared placeholder

#### Scenario: Forbidden interactive tag
- **WHEN** a text contains `<click:run_command:/ban>`
- **THEN** the save is rejected with a Problem Details error

### Requirement: Placeholder parity across locales
The system SHALL check that a non-`en` text declares the same placeholder indices as the `en` text of its key. A key whose `de` text declares a different set MUST be reported as a parity violation in the catalog listing, and the proxy MUST still render it with the `en` text when the placeholder sets differ.

#### Scenario: Parity violation is listed
- **WHEN** the `en` text uses `<arg:0>` and `<arg:1>` and the `de` text uses only `<arg:0>`
- **THEN** the admin listing marks the key as a parity violation

#### Scenario: Proxy falls back on a parity violation
- **WHEN** the proxy renders a key whose `de` text has a parity violation for a `de` player
- **THEN** the proxy renders the `en` text

### Requirement: Catalog export for the proxy

The system SHALL export the catalog to the PROXY role at `GET /i18n/catalog`, returning the entries whose catalog version is greater than `since` together with the current version. The response MUST carry an ETag and answer a matching `If-None-Match` with `304 Not Modified`.

#### Scenario: Incremental export
- **WHEN** a proxy calls `GET /i18n/catalog?since=120` and the catalog version is 125
- **THEN** the response contains only the entries with sequence numbers 121 to 125 and the version 125

#### Scenario: Not modified
- **WHEN** a proxy sends `If-None-Match` with the current ETag
- **THEN** the response is `304 Not Modified` without a body

#### Scenario: Other roles
- **WHEN** a caller without the PROXY role calls `GET /i18n/catalog`
- **THEN** the request is refused under the role rules of the deployment

### Requirement: Admin CRUD for catalog texts
The system SHALL let an `ADMIN` or `MODERATOR` list the keys with their current `en` and `de` text, the version and the parity status, read the version history of a key, and save a new version for a key and locale. Listing MUST use cursor pagination. Expected failures MUST be Problem Details errors with the codes of the standard error model.

#### Scenario: Listing shows missing translations
- **WHEN** a moderator lists the catalog and a key has an `en` text but no `de` text
- **THEN** the entry for that key shows the locale `de` as missing

#### Scenario: History is append-only
- **WHEN** a moderator requests the history of a key
- **THEN** every version is listed with its author's oid and creation time, and none is missing

### Requirement: Change event without content
The system SHALL publish `i18n.catalog_changed` on NATS (subject `blackhole.events.i18n.catalog_changed`) after every committed save. The event MUST carry only the new catalog version and the change time, never the text, the key or any argument.

#### Scenario: Event after save
- **WHEN** a save commits with the new catalog version 126
- **THEN** exactly one `i18n.catalog_changed` event is published with version 126 and no text field

#### Scenario: Event not published on a rejected save
- **WHEN** a save is rejected by validation
- **THEN** no event is published

### Requirement: Locale resolution with fallback
Every consumer SHALL resolve a locale in the chain exact locale, then language, then `en`. For example, `de_AT` resolves to `de_AT`, then `de`, then `en`. A key that has no text in the resolved chain MUST render as the `en` text, and a key that has no `en` text MUST render as its key string, which MUST NOT be shown to a player and MUST be reported by the consumer's logs without any player data.

#### Scenario: Region falls back to language
- **WHEN** a player's locale is `de_AT` and the key has a `de` text
- **THEN** the `de` text is rendered

#### Scenario: Unknown language falls back to English
- **WHEN** a player's locale is `fr_FR` and the key has no `fr` text
- **THEN** the `en` text is rendered

### Requirement: Content texts and bundle texts
The system SHALL keep the texts of the UI of the panel, the portal and the bot in their own bundles (`web/src/locales`, `portal/src/locales`, `discord/.../Lang`). Only texts that are content, meaning they are created or chosen by staff or are part of a staff-maintained entity, MUST be catalog texts. The proxy's bundled `blackhole_*.properties` MUST stay as the offline fallback of the proxy only.

#### Scenario: A template name is a catalog text
- **WHEN** a template is created with the name "Chat abuse"
- **THEN** the name is stored as the `en` text of `template.<key>.name`

#### Scenario: A button label is a bundle text
- **WHEN** the panel renders the label of a save button
- **THEN** the label comes from `web/src/locales`, not from the catalog

### Requirement: Migration of existing texts
The migration SHALL import the current English texts of the proxy bundle and of the existing content rows as `en` version 1, and the German texts of `blackhole_de.properties` as `de` version 1 where a key exists in both. The migration MUST be idempotent: a second run MUST NOT create a version for a key that already has a text for that locale.

#### Scenario: German default is imported
- **WHEN** the migration runs on a database with `punishment.reason.escalated` in the German bundle
- **THEN** a `de` version 1 row exists for the corresponding catalog key

#### Scenario: Re-run is a no-op
- **WHEN** the migration runs a second time
- **THEN** no new rows are added
