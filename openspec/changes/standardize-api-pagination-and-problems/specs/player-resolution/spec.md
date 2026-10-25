## MODIFIED Requirements

### Requirement: Player lookup endpoint
The backend SHALL expose `GET /player/resolve/{name}` (API version 1) returning HTTP 200 with `uuid`, `name` and `source` when the name is resolved, and HTTP 404 as an RFC 9457 problem (`application/problem+json`) when it is not. The problem `detail` SHALL carry the same message as the previous `errorMessage`.

#### Scenario: Name resolved
- **WHEN** a client requests `GET /player/resolve/Notch` and a resolver finds the player
- **THEN** the response is 200 with the player's UUID, name and the source that resolved it (`otis`, `mojang` or `namemc`)

#### Scenario: Name not found
- **WHEN** no enabled resolver finds the requested name
- **THEN** the response is 404 as a problem whose `detail` is "No resolver found a player named <name>"
