-- Appeal conditions (appeal-conditions capability): the rules a player confirms before filing an appeal.
--
-- The table is append-only: a save inserts the next version number, a version is never updated or deleted.
-- The primary key makes two concurrent saves of the same number fail on one side. recorded_by_oid is the
-- verified staff member (no foreign key, like the other recorded_by_oid columns); it is NULL for the
-- seeded default. The version a player accepted is kept in the appeal's own meta_data (conditionsVersion),
-- so this migration does not touch appeals and existing appeals simply carry no version.
CREATE TABLE appeal_conditions (
    version integer NOT NULL,
    body text NOT NULL,
    created_at bigint NOT NULL,
    recorded_by_oid uuid,
    CONSTRAINT appeal_conditions_pkey PRIMARY KEY (version)
);

-- Version 1: the default English text. Plain text; a blank line separates paragraphs, "- " starts a bullet.
INSERT INTO appeal_conditions (version, body, created_at, recorded_by_oid)
VALUES (
    1,
    'Please read these conditions before you file an appeal.

- Be respectful. Insults and threats towards the team can end your appeal.
- You may file one appeal per Ban ID.
- Sharing your Ban ID, or a screenshot of it, forfeits your right to appeal.
- After a rejection you have to wait before you can appeal again. The date is shown to you.
- The team decides, and the decision is final for this appeal.
- We cannot promise how fast we answer.
- Do not open the same appeal through Discord and through the portal.
- Do not write personal data, such as addresses or details about other people, in your messages.',
    (extract(epoch FROM now()) * 1000)::bigint,
    NULL
);
