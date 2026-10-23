# Spec Delta

## Purpose
Shows the player the Ban ID they need for the portal.

## ADDED Requirements

### Requirement: Ban screen shows the Ban ID
The proxy SHALL show the Ban ID of the active punishment on the ban screen and chat-ban message, fetched from the backend when the screen is rendered and kept only in plugin memory. When the backend cannot be reached it MUST show the screen without a Ban ID and MUST NOT show a legacy hex short id in its place; enforcement MUST NOT depend on this call.

#### Scenario: Banned player joins
- **WHEN** a banned player joins and the backend answers
- **THEN** the screen shows the reason, the duration and the Ban ID

#### Scenario: Backend down
- **WHEN** the backend call fails
- **THEN** the player is still denied and the screen has no Ban ID

#### Scenario: Not persisted
- **WHEN** the plugin restarts
- **THEN** no Ban ID is read from disk or the shared cache
