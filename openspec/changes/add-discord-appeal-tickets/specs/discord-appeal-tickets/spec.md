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
- **THEN** an appeal is filed, a ticket with a number is created and a private channel exists for that player, the bot and the linked staff

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
Each ticket SHALL have one channel in the ticket category named from its ticket number (no Ban ID, name or hash), visible only to the bound player, the bot and the staff members allowed by the staff access requirement, with the player denied Attach Files and Embed Links. Earlier player-visible messages of the case MUST be posted in order when it is created. A crash between creating the ticket and the channel MUST be completed at the next start without a second channel.

#### Scenario: Permissions
- **WHEN** the channel is created
- **THEN** `@everyone` cannot view it, the staff role as such has no overwrite and the player cannot attach files

#### Scenario: Crash before the channel exists
- **WHEN** the bot restarts with a ticket in `PENDING_CHANNEL`
- **THEN** exactly one channel is created or reused and the ticket becomes `OPEN`

### Requirement: Staff access requires a live link
Staff without a linked Discord account SHALL NOT be allowed in ticket channels. The bot MUST add, as per-channel member overwrites, exactly the members who hold `DISCORD_STAFF_ROLE_ID` and have a live staff link, and MUST give the staff role itself no permission on the ticket and archive categories.

#### Scenario: Linked role holder
- **WHEN** a ticket channel is created and a staff member with the role has a live link
- **THEN** that member can view and write in the channel

#### Scenario: Role holder without a link
- **WHEN** a member holds the staff role but has no staff link
- **THEN** they cannot view the ticket channel

#### Scenario: Link without the role
- **WHEN** a linked member does not hold the staff role
- **THEN** they are not added

#### Scenario: Misconfigured category
- **WHEN** the staff role holds View on the ticket category
- **THEN** the start self-check reports it

### Requirement: Staff access is reconciled
The bot SHALL reconcile the allowed staff set at ticket creation, when a staff link is removed and every `DISCORD_STAFF_RECONCILE_INTERVAL` (default PT5M), removing members who lost the link or the role, without a member cache or the `GUILD_MEMBERS` intent.

#### Scenario: Unlink removes access
- **WHEN** a linked staff member runs `/unlink`
- **THEN** their overwrite is removed from every ticket channel at the next reconcile and their earlier messages stay as recorded

#### Scenario: Role removed
- **WHEN** a linked member loses the staff role
- **THEN** they are removed from the ticket channels at the next reconcile

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
When the appeal is decided the bot SHALL post the decision message, lock the channel for the player, complete the transcript, obtain the stored protocol through the close request (see `discord-transcript`) and only then move the channel to the archive category. A closed ticket MUST NOT be re-opened, and the backend MUST refuse the state `ARCHIVED` without the protocol.

#### Scenario: Decision closes the ticket
- **WHEN** a moderator denies the appeal
- **THEN** the channel is locked, the transcript is completed, the protocol is stored and the channel is moved to the archive category

#### Scenario: New appeal after a denial
- **WHEN** the player files again after the cooldown
- **THEN** a new ticket and channel are created and the old ones stay closed

### Requirement: Archived channels are deleted after 30 days
An archived channel SHALL be deleted `DISCORD_ARCHIVE_DELETE_AFTER` after it was archived (default 30 days, `PT0S` keeps archives), and MUST NOT be deleted before the ticket has `ticket.transcript_complete` and a stored current protocol. The backend MUST refuse the state `DELETED` without them.

#### Scenario: Delete only when complete
- **WHEN** the delete delay has passed for a ticket without `ticket.transcript_complete`
- **THEN** the channel is kept

#### Scenario: Delete only with a protocol
- **WHEN** the delete delay has passed for a complete ticket whose protocol is missing
- **THEN** the channel is kept and the state change to `DELETED` is refused with HTTP 409

#### Scenario: Delete after the delay
- **WHEN** the delay has passed for a complete ticket with a current protocol
- **THEN** the channel is deleted and the case records `ticket.channel_deleted`

#### Scenario: Thirty day default
- **WHEN** the setting is not configured
- **THEN** an archived channel is deleted 30 days after it was archived

### Requirement: Failure behaviour
The bot MUST degrade without losing recorded data: with the backend unreachable it retries with backoff and answers modals with a generic retry message; with the broker unreachable the cursor poll continues; a missing Discord permission is reported at start and through the health endpoint.

#### Scenario: Backend unreachable during open
- **WHEN** the backend is down when a player submits the modal
- **THEN** the player is told to try again later and nothing is half-created

#### Scenario: Edge limit answers 429
- **WHEN** the edge answers 429 to a ticket-open request
- **THEN** the player gets the same generic retry message, the bot makes no immediate retry loop and keeps no per-user cooldown

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

### Requirement: Bot ingress is limited at the edge only
The bot and the backend MUST NOT implement a per-user cooldown, attempt counter or other application-level rate limit for ticket opening or leak checks. The operator documentation SHALL state the required edge rule for the bot's traffic: a global limit on `POST /discord/tickets` (suggested 30 requests per minute) and on `POST /discord/leak-check` (suggested 120 requests per minute), answering 429.

#### Scenario: Rapid attempts by one user
- **WHEN** one Discord user submits the modal repeatedly with wrong Ban IDs
- **THEN** each attempt gets the generic reply and the application keeps no per-user attempt state

#### Scenario: Documented edge rule
- **WHEN** the deployment documentation is reviewed
- **THEN** it names both routes, a starting limit and the 429 behaviour
