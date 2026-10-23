# Spec Delta

## Purpose
Lets a banned player appeal in a private Discord channel that is synchronised both ways with the portal and the panel, run by a separate bot that is optional for everything else.

## ADDED Requirements

### Requirement: Separate bot module and runtime
The system SHALL provide a Gradle module `discord` that builds a JDA-based bot as its own container. It MUST authenticate to the backend with the OAuth2 client credentials grant, use only the `GUILDS`, `GUILD_MESSAGES` and `MESSAGE_CONTENT` gateway intents, hold no database, and fail to start with a message naming every missing or malformed environment variable.

#### Scenario: Missing configuration
- **WHEN** the container starts without `DISCORD_TOKEN`
- **THEN** it exits non-zero and names the variable

#### Scenario: Stateless restart
- **WHEN** the container is replaced
- **THEN** the new instance resumes every open ticket from the backend state alone

#### Scenario: Intents
- **WHEN** the JDA builder is composed in the test
- **THEN** the intents are exactly the three listed and the member cache is off

### Requirement: Panel and portal do not depend on the bot
The panel, the portal and the backend MUST keep working when the bot is down, and messages written meanwhile MUST reach the Discord channel when it is back.

#### Scenario: Bot offline
- **WHEN** the bot is stopped and a moderator replies in the panel
- **THEN** the reply is recorded and, after the bot starts, appears in the channel once

### Requirement: Opening a ticket with a private Ban ID
A player SHALL open an appeal ticket with a slash command or a button, entering the Ban ID (and a statement when no appeal is open) in an ephemeral modal. The backend MUST validate the Ban ID as the portal login does, answer a wrong, unknown, leaked or already bound ID with the same generic reply, file the appeal under the portal's rules, and bind the Discord user to the ticket. The Ban ID MUST NOT appear in any channel, log or event.

#### Scenario: Successful open
- **WHEN** a player submits a valid Ban ID and a statement
- **THEN** an appeal is filed, a ticket with a number is created and a private channel exists for that player and the staff role

#### Scenario: Wrong Ban ID
- **WHEN** the Ban ID is unknown
- **THEN** the player gets the generic ephemeral reply and nothing is created

#### Scenario: Ticket already bound to someone else
- **WHEN** a second Discord user submits the Ban ID of a ticket bound to another user
- **THEN** they get the same generic reply as for a wrong ID

#### Scenario: Statement missing
- **WHEN** no appeal is open and the statement is empty
- **THEN** the player is asked for a statement and nothing is created

#### Scenario: Appeal not yet allowed
- **WHEN** the waiting period has not passed
- **THEN** the reply names the date it becomes possible and no ticket is created

### Requirement: One private channel per ticket
Each ticket SHALL have one channel in the ticket category named from its ticket number (no Ban ID, name or hash), visible only to the bound player, the staff role and the bot, with the player denied Attach Files and Embed Links. Earlier player-visible messages of the case MUST be posted in order when it is created. A crash between creating the ticket and the channel MUST be completed at the next start without a second channel.

#### Scenario: Permissions
- **WHEN** the channel is created
- **THEN** `@everyone` cannot view it and the player cannot attach files

#### Scenario: Crash before the channel exists
- **WHEN** the bot restarts with a ticket in `PENDING_CHANNEL`
- **THEN** exactly one channel is created or reused and the ticket becomes `OPEN`

### Requirement: Both-way synchronisation
A message recorded on the case with `PLAYER` visibility from the portal or the panel SHALL be posted into the open ticket channel in `seq` order, and every message written in the channel SHALL be recorded on the case. A message MUST NOT be relayed back to where it came from, and the bot MUST NOT ingest its own messages.

#### Scenario: Panel reply reaches Discord
- **WHEN** a moderator replies in the panel
- **THEN** the channel shows it as from the moderation team, without the moderator's name

#### Scenario: Discord message reaches the portal
- **WHEN** the player writes in the channel
- **THEN** the case has a `message.added` event with channel `DISCORD` and the portal shows it

#### Scenario: No loop
- **WHEN** a relayed message is posted by the bot
- **THEN** it is not recorded as a new message

### Requirement: Idempotent and resumable relay
Relaying SHALL be driven by a per-ticket cursor read from the backend, with `case.*` events only as a hint, so that a lost, duplicated or reordered broker message changes nothing. A relay MUST NOT be repeated after a restart once its link was recorded.

#### Scenario: Broker message lost
- **WHEN** the `case.message_added` event never reaches the bot
- **THEN** the next cursor poll relays the message

#### Scenario: Restart after relaying
- **WHEN** the bot restarts after relaying and linking a message
- **THEN** it is not posted again

### Requirement: Edits and deletes are mirrored
An edit or delete made in the channel SHALL be recorded as `message.edited` or `message.deleted`, and an edit or delete of a relayed message on the case SHALL edit or delete its Discord copy. The recorded history MUST never be overwritten.

#### Scenario: Player edits a message
- **WHEN** the player edits a message in the channel
- **THEN** a `message.edited` event exists and the original text is still in the record

#### Scenario: Player deletes a message
- **WHEN** the player deletes a message
- **THEN** a `message.deleted` event exists and staff can still read the last text

### Requirement: Freeze and thaw
A leak mark after filing SHALL deny the bound player the ability to send in the channel and post a notice; a reissue SHALL restore it. Staff MUST keep the ability to write.

#### Scenario: Leak after filing
- **WHEN** the Ban ID is marked leaked while a ticket is open
- **THEN** the player can no longer send messages in the channel and staff can

#### Scenario: Reissue
- **WHEN** an admin reissues the Ban ID
- **THEN** the player can send again

### Requirement: Decision, lock and archive
When the appeal is decided the bot SHALL post the decision message, lock the channel for the player, complete the transcript and move the channel to the archive category. A channel MUST NOT be deleted before the ticket has `ticket.transcript_complete`; after `DISCORD_ARCHIVE_DELETE_AFTER` (default 30 days, `PT0S` keeps archives) it MUST be deleted, and a closed ticket MUST NOT be re-opened.

#### Scenario: Decision closes the ticket
- **WHEN** a moderator denies the appeal
- **THEN** the channel is locked, the transcript is completed and the channel is moved to the archive category

#### Scenario: Delete only when complete
- **WHEN** the delete delay has passed for a ticket without `ticket.transcript_complete`
- **THEN** the channel is kept

#### Scenario: Delete after the delay
- **WHEN** the delay has passed for a complete ticket
- **THEN** the channel is deleted and the case records `ticket.channel_deleted`

#### Scenario: New appeal after a denial
- **WHEN** the player files again after the cooldown
- **THEN** a new ticket and channel are created and the old ones stay closed

### Requirement: Failure behaviour
The bot MUST degrade without losing recorded data: with the backend unreachable it retries with backoff and answers modals with a generic retry message; with the broker unreachable the cursor poll continues; a missing Discord permission is reported at start and through the health endpoint.

#### Scenario: Backend unreachable during open
- **WHEN** the backend is down when a player submits the modal
- **THEN** the player is told to try again later and nothing is half-created

#### Scenario: Missing permission
- **WHEN** the bot lacks Manage Channels on the ticket category
- **THEN** the health endpoint reports it and the start log names the category

### Requirement: Discord role in Entra
The system SHALL define the app role `Blackhole.Discord` and a `Blackhole Discord (<env>)` client registration in `docs/entra-setup.sh` and `docs/entra-setup.md`. A caller with this role MUST be allowed only the `/discord/**` routes and MUST be denied every other route; staff roles MUST be denied `/discord/**`.

#### Scenario: Bot token on a case route
- **WHEN** a token with `Blackhole.Discord` calls `GET /case`
- **THEN** the response is HTTP 403

#### Scenario: Moderator on the facade
- **WHEN** a moderator calls `POST /discord/tickets`
- **THEN** the response is HTTP 403

#### Scenario: Setup script
- **WHEN** `docs/entra-setup.sh` runs twice
- **THEN** the role and registration exist once and the env file has the `BLACKHOLE_DISCORD_*` lines

### Requirement: Testability behind interfaces
The sync, ticket and leak logic MUST depend only on the interfaces `DiscordGateway` and `BackendGateway` and on an injected clock and scheduler, so the tests run with in-memory fakes, no network and no sleeps.

#### Scenario: Core has no JDA import
- **WHEN** the architecture test inspects the core packages
- **THEN** none imports `net.dv8tion`
