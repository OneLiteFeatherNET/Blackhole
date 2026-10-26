# Spec Delta

## Purpose
Localises the player-facing and staff-facing messages of the Velocity proxy through the backend catalog, with a live refresh and a bundled offline fallback.

## MODIFIED Requirements

### Requirement: Localized messages
The proxy SHALL render every player-facing and staff-facing message from the backend translation catalog (`i18n-catalog`), using the locale of the viewer: the client locale of the player for player-facing messages, and the client locale of the staff member for staff-facing messages. The proxy SHALL register one custom Adventure translator with the global translator, and the translator SHALL read the current catalog snapshot on every translation, so that a refreshed catalog takes effect without re-registration or restart. Messages that depend on player-supplied text MUST pass that text as an argument that is never parsed as MiniMessage. The bundled `blackhole_en.properties` and `blackhole_de.properties` remain as the fallback when no catalog has been loaded yet. The messages include `punishment.error.muted`, `punishment.success.ban`, `punishment.success.unban`, `punishment.success.unmute`, the punishment screens and the related error keys.

#### Scenario: German client
- **WHEN** a muted player whose client locale is German sends a chat message
- **THEN** the denial message is shown in German

#### Scenario: Region falls back to language
- **WHEN** a player whose client locale is `de_AT` is denied and the catalog has a `de` text for the key
- **THEN** the `de` text is shown

#### Scenario: Unknown locale falls back to English
- **WHEN** a player whose client locale is `fr_FR` is denied and the catalog has no `fr` text
- **THEN** the `en` text is shown

#### Scenario: Live update without restart
- **WHEN** a staff member saves a new `en` text for a key and the proxy refreshes its catalog
- **THEN** the next message for that key shows the new text and the proxy process is not restarted

#### Scenario: Player text is not parsed as markup
- **WHEN** a player's name or a staff-written reason contains `<red>` or `<click:run_command:/ban>`
- **THEN** the text is shown literally and no tag from it takes effect

#### Scenario: Backend unreachable at startup
- **WHEN** the proxy starts while the backend is unreachable and a catalog file from an earlier run exists on disk
- **THEN** the proxy uses that catalog and the bundled texts for every key the file does not contain

#### Scenario: No catalog at all
- **WHEN** the proxy starts while the backend is unreachable and no catalog file exists
- **THEN** the proxy renders the bundled texts and logs one warning without any player data
