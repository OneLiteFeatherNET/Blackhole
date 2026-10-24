-- Discord appeal tickets (discord-appeal-tickets and discord-transcript capabilities): a private Discord
-- channel per appeal, synchronised with the case conversation. The bot is stateless; everything it needs to
-- resume lives here.
--
-- case_participant is the only place a Discord user id of a conversation partner is stored. Events,
-- logs, metrics and the export refer to the opaque id. Erasing a participant nulls the Discord id and the
-- name and sets erased_at; the timeline keeps its structure. The unique pair lets one Discord user be one
-- participant of a case; an erased row (NULL id) never collides.
--
-- discord_ticket is one channel for one appeal. At most one ticket per appeal and player is open at a time
-- (PENDING_CHANNEL, OPEN, CLOSING); a closed ticket is never re-opened, a new one gets a new row and number.
-- ticket_no is the number in the channel name. ingested_cursor is the highest Discord message id recorded,
-- relayed_seq the highest case_event.seq relayed to the channel. Discord snowflakes fit a signed bigint.
-- state is validated by the application, not by a CHECK constraint (see .claude/rules/database.md).
--
-- discord_message_link maps a Discord message to the case message (message_id is the case_event subject).
-- INBOUND: written in Discord and recorded on the case; RELAYED: written on the case and posted to
-- Discord. text_hash is the hash of the latest known text, so an edit is recorded only when the text differs;
-- deleted marks a Discord message that is gone.
CREATE TABLE case_participant (
    id uuid NOT NULL,
    punishment_identifier varchar(255) NOT NULL,
    kind varchar(16) NOT NULL,
    discord_user_id varchar(24),
    display_name varchar(128),
    bot boolean NOT NULL DEFAULT false,
    created_at bigint NOT NULL,
    erased_at bigint,
    CONSTRAINT case_participant_pkey PRIMARY KEY (id),
    CONSTRAINT fk_case_participant_punishment FOREIGN KEY (punishment_identifier)
        REFERENCES punishments (identifier) ON DELETE CASCADE,
    CONSTRAINT uq_case_participant_discord UNIQUE (punishment_identifier, discord_user_id)
);
CREATE INDEX idx_case_participant_punishment ON case_participant (punishment_identifier);

CREATE TABLE discord_ticket (
    id uuid NOT NULL,
    ticket_no bigint GENERATED ALWAYS AS IDENTITY,
    punishment_identifier varchar(255) NOT NULL,
    appeal_id uuid NOT NULL,
    owner_participant_id uuid NOT NULL,
    state varchar(16) NOT NULL,
    channel_id varchar(24),
    guild_id varchar(24),
    frozen boolean NOT NULL DEFAULT false,
    ingested_cursor bigint NOT NULL DEFAULT 0,
    relayed_seq bigint NOT NULL DEFAULT 0,
    transcript_count integer,
    transcript_last_message_id bigint,
    transcript_complete_at bigint,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL,
    closed_at bigint,
    archived_at bigint,
    deleted_at bigint,
    CONSTRAINT discord_ticket_pkey PRIMARY KEY (id),
    CONSTRAINT uq_discord_ticket_no UNIQUE (ticket_no),
    CONSTRAINT fk_discord_ticket_punishment FOREIGN KEY (punishment_identifier)
        REFERENCES punishments (identifier) ON DELETE CASCADE,
    CONSTRAINT fk_discord_ticket_owner FOREIGN KEY (owner_participant_id)
        REFERENCES case_participant (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_discord_ticket_open ON discord_ticket (appeal_id, owner_participant_id)
    WHERE state IN ('PENDING_CHANNEL', 'OPEN', 'CLOSING');
CREATE UNIQUE INDEX uq_discord_ticket_channel ON discord_ticket (channel_id) WHERE channel_id IS NOT NULL;
CREATE INDEX idx_discord_ticket_punishment ON discord_ticket (punishment_identifier);
CREATE INDEX idx_discord_ticket_state ON discord_ticket (state);

CREATE TABLE discord_message_link (
    ticket_id uuid NOT NULL,
    discord_message_id bigint NOT NULL,
    message_id uuid NOT NULL,
    direction varchar(8) NOT NULL,
    text_hash varchar(64),
    deleted boolean NOT NULL DEFAULT false,
    created_at bigint NOT NULL,
    CONSTRAINT discord_message_link_pkey PRIMARY KEY (ticket_id, discord_message_id),
    CONSTRAINT fk_discord_message_link_ticket FOREIGN KEY (ticket_id)
        REFERENCES discord_ticket (id) ON DELETE CASCADE
);
CREATE INDEX idx_discord_message_link_message ON discord_message_link (ticket_id, message_id);
