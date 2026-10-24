# Spec Delta

## Purpose
Lets staff define rules over the client brand, legacy mod list, registered plugin channels, protocol version and commands, scoped globally or to sections and servers, with a per-rule action (notify, deny, apply a template). Detection is best effort: brand, mods and channels are chosen by the client and can be faked, so a hit is a signal and not proof.

## ADDED Requirements

### Requirement: Rule model
A client rule SHALL have a unique key (`[a-z0-9-]`, 1 to 64 characters), a display name, a kind (`BRAND`, `MOD`, `CHANNEL`, `PROTOCOL`, `COMMAND`), an enabled flag, a scope, an action and the match data of its kind. Fields that do not belong to the kind MUST be rejected.

#### Scenario: Create a channel rule
- **WHEN** an admin creates a `CHANNEL` rule with the pattern `meteor-client:*`
- **THEN** the rule is stored enabled with the action `NOTIFY` and the pattern in lower case

#### Scenario: Wrong fields for the kind
- **WHEN** a `PROTOCOL` rule carries patterns, or a `BRAND` rule carries a protocol range
- **THEN** the request is rejected with a validation error

#### Scenario: Duplicate key
- **WHEN** a rule is created with an existing key
- **THEN** the response is a conflict and the existing rule is unchanged

### Requirement: Rule match data
`BRAND`, `MOD`, `CHANNEL` and `COMMAND` rules SHALL hold 1 to 32 case-insensitive glob patterns of at most 128 characters; `PROTOCOL` rules SHALL hold a protocol range with minimum not above maximum. A `BRAND` or `PROTOCOL` rule MAY be negated to hit everything outside its match. Regular expressions MUST NOT be accepted.

#### Scenario: Invalid pattern
- **WHEN** a rule is submitted with a pattern containing characters outside the allowed set or longer than 128 characters
- **THEN** the request is rejected with a validation error naming the field and nothing is stored

#### Scenario: Negated protocol range
- **WHEN** a negated `PROTOCOL` rule covers 765 to 774
- **THEN** a client with protocol 47 hits it and a client with 767 does not

### Requirement: Rule scope
A rule SHALL be `GLOBAL` or limited to servers. A server-limited rule SHALL name existing SERVER sections by key and/or carry its own target kind (`PATTERN`, `CLOUDNET_TASK`, `CLOUDNET_GROUP`, `CLOUDNET_SERVICE`) with names, and SHALL cover a server exactly when the section targeting would cover it. A section key that does not exist or is not a SERVER section MUST be rejected on write. A rule whose section was deleted later SHALL cover nothing for that key.

#### Scenario: Rule limited to a section
- **WHEN** a rule names the section `minigames` and a player is on a server that section covers
- **THEN** the rule applies to that player

#### Scenario: Rule limited to a CloudNet task
- **WHEN** a rule has the target kind `CLOUDNET_TASK` with the name `BedWars` and a player is on a service of that task
- **THEN** the rule applies

#### Scenario: Unknown section
- **WHEN** a rule names a section key that does not exist
- **THEN** the request is rejected with a validation error

#### Scenario: Server unknown to CloudNet
- **WHEN** a CloudNet-scoped rule is checked for a server CloudNet does not know
- **THEN** the rule does not cover it and one warning per server name is logged

### Requirement: Action per rule
Each rule SHALL carry exactly one action chosen by the admin: `NOTIFY` (the default), `DENY` or `TEMPLATE`. `NOTIFY` SHALL record the hit and tell the team and change nothing for the player. `DENY` SHALL additionally refuse the connection or the command. `TEMPLATE` SHALL additionally apply the rule's punishment template, which MUST exist.

#### Scenario: Default action
- **WHEN** a rule is created without an action
- **THEN** its action is `NOTIFY`

#### Scenario: Notify only
- **WHEN** a player hits a `NOTIFY` rule
- **THEN** the hit is recorded and online staff are told
- **AND** the player is neither refused nor punished

#### Scenario: Deny with the rule's message
- **WHEN** a player hits a `DENY` rule with the message "This client is not allowed here."
- **THEN** the player is refused and sees exactly that message

#### Scenario: Default message
- **WHEN** a `DENY` rule has no message
- **THEN** the player sees a fixed English default for the rule's kind

#### Scenario: Message too long
- **WHEN** a deny message exceeds 200 characters
- **THEN** the request is rejected with a validation error

#### Scenario: Template does not exist
- **WHEN** a rule names a template key that does not exist
- **THEN** the request is rejected with a validation error

### Requirement: Spoofable signals need an acknowledgement for punishments
A rule of kind `BRAND`, `MOD` or `CHANNEL` with the action `TEMPLATE` SHALL be accepted only when it carries an explicit acknowledgement that the signal is client-controlled. The acknowledgement MUST be stored with the rule, MUST be cleared when the kind or action is changed, and MUST NOT be required for `PROTOCOL` and `COMMAND` rules.

#### Scenario: Punishing on a brand without acknowledgement
- **WHEN** an admin saves a `BRAND` rule with the action `TEMPLATE` and no acknowledgement
- **THEN** the request is rejected with a validation error explaining that the signal can be faked

#### Scenario: Acknowledged
- **WHEN** the same rule is saved with the acknowledgement
- **THEN** it is stored and flagged as acknowledged

#### Scenario: Protocol rule
- **WHEN** a `PROTOCOL` rule with the action `TEMPLATE` is saved without an acknowledgement
- **THEN** it is accepted

### Requirement: Template action applies an automatic punishment
When a player hits a `TEMPLATE` rule the backend SHALL apply the template through the same path as the ELO automatic punishments: no human actor, a system source naming the rule, a Ban ID, the template's steps and ELO cost, and open to appeal. It MUST NOT apply the template again while the player has an active punishment from the same rule. If the backend cannot be reached nothing is applied.

#### Scenario: First hit
- **WHEN** a player hits a `TEMPLATE` rule and has no active punishment from it
- **THEN** a punishment from the rule's template exists for the player, marked as automatic with the rule's key as source
- **AND** the player can appeal it

#### Scenario: Repeated hit
- **WHEN** the same player hits the rule again while that punishment is active
- **THEN** no further punishment is created and the step does not escalate

#### Scenario: Backend unreachable
- **WHEN** the proxy cannot report the hit
- **THEN** the player is not punished and the team notice still appears

### Requirement: Evaluation happens on the proxy
The proxy SHALL evaluate enabled rules against the player's traits from its in-memory copy of the rules without a backend round trip per check. The copy SHALL be loaded at start and reloaded when rules change. With no rules loaded, including a backend that does not know the routes, nothing is enforced.

#### Scenario: Backend down
- **WHEN** the backend is unreachable and a `DENY` rule hits
- **THEN** the player is still refused

#### Scenario: Rules changed
- **WHEN** an admin disables a rule
- **THEN** every proxy stops applying it after the change notification

### Requirement: When each kind is checked
`PROTOCOL` rules SHALL be checked at login (global) or when connecting to a covered server (scoped). `BRAND`, `MOD` and `CHANNEL` rules SHALL be checked whenever the corresponding client trait arrives and at each server connect, and MUST NOT refuse a login because those traits are not reliably known then. `COMMAND` rules SHALL be checked when a command is executed, against the server the player is on.

#### Scenario: Global protocol rule
- **WHEN** a client with a protocol below the rule's minimum logs in and the rule is a global `DENY`
- **THEN** the login is refused with the rule's message

#### Scenario: Brand arrives after login
- **WHEN** a player logs in and the brand arrives later and matches a `DENY` brand rule covering the player's server
- **THEN** the player is disconnected with the rule's message when the brand arrives

#### Scenario: Negated brand without a brand
- **WHEN** a client never sends a brand and a negated brand rule exists
- **THEN** the rule does not hit

### Requirement: Command rules
A `COMMAND` rule SHALL match the first word of a command line after removing leading spaces and a leading `/` and lower-casing, for commands the proxy handles and for commands it forwards to a backend server. A word of the form `namespace:label` SHALL be matched as written and as `label`. Arguments MUST NOT be matched. A denied command SHALL be neither executed on the proxy nor forwarded.

#### Scenario: Forwarded command
- **WHEN** a player on a covered server types `/op me` and a `DENY` rule has the pattern `op`
- **THEN** the command is not forwarded and the player sees the rule's message

#### Scenario: Namespaced form
- **WHEN** the player types `/minecraft:op me`
- **THEN** the rule for `op` also hits

#### Scenario: Leading spaces
- **WHEN** the player types `/  op me`
- **THEN** the rule hits

#### Scenario: Other server
- **WHEN** the rule is limited to the section `minigames` and the player is on a server outside it
- **THEN** the command runs normally

### Requirement: Signed commands need SignedVelocity
A command carrying signed arguments SHALL be denied only when SignedVelocity is loaded on the proxy, because cancelling such a command otherwise makes Velocity disconnect the player. Commands that are unsigned, signed without arguments or from clients that do not sign commands MAY be denied without it.

#### Scenario: Signed command with SignedVelocity
- **WHEN** a player sends a command with signed arguments that a `DENY` rule matches and SignedVelocity is loaded
- **THEN** the command is denied and the player stays connected

#### Scenario: Signed command without SignedVelocity
- **WHEN** the same happens without SignedVelocity
- **THEN** the command is delivered, the player is told it is not allowed, and the hit is recorded as not enforced (a signed command that cannot be denied is always delivered)

#### Scenario: Startup warning
- **WHEN** the plugin starts without SignedVelocity
- **THEN** one warning names both chat and commands

#### Scenario: Unsigned command without SignedVelocity
- **WHEN** a click-run command from a 1.20.5+ client matches a `DENY` rule and SignedVelocity is missing
- **THEN** the command is denied

### Requirement: Hiding commands
A `COMMAND` rule MAY ask for its commands to be hidden. The proxy SHALL then remove those commands from the command list sent to players on covered servers. Hiding MUST NOT replace denial and MUST NOT apply to players holding the bypass permission.

#### Scenario: Hidden from tab completion
- **WHEN** a rule with hiding covers the player's server
- **THEN** the command does not appear in the player's tab completion

### Requirement: Staff bypass
Players holding the permission `blackhole.clientrules.bypass` MUST NOT be refused or punished by any rule. Their hits SHALL still be recorded, marked as bypassed.

#### Scenario: Staff types a denied command
- **WHEN** a player with the bypass permission types a denied command
- **THEN** the command runs and a bypassed hit is recorded

### Requirement: Hits carry no client traits
A rule hit SHALL be stored with the rule key, the hashed owner, the time, the action taken, whether it was enforced or bypassed and the server name. It MUST NOT store or publish the brand, channel, mod or command text. Hits SHALL be deleted after the configured retention (default 30 days) while the rule keeps a total count, and SHALL be deleted when the player's data is erased. A hit SHALL be published as a domain event with the rule key, owner hash, kind, action and time only.

#### Scenario: Hit stored
- **WHEN** a hit on a `CHANNEL` rule is reported
- **THEN** the stored hit contains no channel name

#### Scenario: Retention
- **WHEN** a hit is older than the retention
- **THEN** the daily sweep deletes it and the rule's total count is unchanged

#### Scenario: Erasure
- **WHEN** a player's data is erased
- **THEN** the player's hits are deleted

### Requirement: Team notification
Online staff holding `blackhole.clientrules.notify` SHALL be told in game of a hit on every proxy, once per hit, with the rule's display name, the player name, the kind, the action and whether it was enforced. The notice MUST travel only over the transient pub/sub channel and MUST NOT be stored. When pub/sub is unavailable the proxy that saw the hit SHALL tell its own staff.

#### Scenario: Staff on another proxy
- **WHEN** a hit happens on proxy A and a staff member is on proxy B
- **THEN** the staff member is told once

#### Scenario: Repeated hit in one session
- **WHEN** the same player hits the same rule again in the same session
- **THEN** staff are not told again

### Requirement: Catalogue for the rule editor
The proxy SHALL report a catalogue of the brands and channel ids seen from at least three distinct players, the command words in the proxy's command tree and the protocol versions it supports. Command words MUST come from the command tree and not from what players typed. The backend SHALL keep it in the shared cache with a time to live. A cache outage SHALL show the catalogue as unavailable and MUST NOT affect enforcement.

#### Scenario: Rare channel
- **WHEN** a channel id was registered by only two players
- **THEN** it is not part of the catalogue

#### Scenario: Typed text
- **WHEN** a player types an unknown word as a command
- **THEN** it does not appear in the catalogue

#### Scenario: Cache outage
- **WHEN** the shared cache is unavailable
- **THEN** reading the catalogue answers `available: false` and rules keep working

### Requirement: Routes and roles
Rules and hits SHALL be readable by every team role (`VIEWER` and up); creating, changing and deleting rules SHALL need `ADMIN`; the proxy SHALL read rules and write the catalogue and hits with the `PROXY` role only on its own routes; the proxy MUST be denied on the hit and catalogue reads. Every write SHALL record the token's `oid`.

#### Scenario: Moderator edits a rule
- **WHEN** a `MODERATOR` creates a rule
- **THEN** the response is 403

#### Scenario: Viewer reads
- **WHEN** a `VIEWER` lists rules and hits
- **THEN** the response is 200

#### Scenario: Proxy reports a hit
- **WHEN** the proxy posts a hit for an enabled rule
- **THEN** the response is 200

#### Scenario: Unknown rule key in a hit
- **WHEN** the proxy posts a hit for a key that does not exist
- **THEN** the response is 404 and nothing is stored

### Requirement: Player-facing text is English
Every message shown to a player by a rule SHALL be English, regardless of the player's locale.

#### Scenario: German client
- **WHEN** a player with a German locale is refused by a rule
- **THEN** the message is English
