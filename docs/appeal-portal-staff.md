# Appeals in the panel: Ban IDs, conversations and protocols

For staff who work appeals in the admin panel. The roles used below are `VIEWER`, `MODERATOR` and `ADMIN`; each
includes the ones before it. Setting up the portal is in [`docs/appeal-portal.md`](appeal-portal.md).

## 1. The Ban ID

The Ban ID is the player's key to the appeal portal: 8 characters from digits and letters without `I`, `L`, `O`
and `U`. Every punishment has one. The player sees it on the ban screen; the
portal accepts it typed in any case, with spaces or a leading `#`, and reads `O` as `0` and `I` or `L` as `1`.

It is a credential, so treat it like a password:

- **Reveal** it on the case page, section *Ban ID*, with *Reveal Ban ID* (`MODERATOR` and above; a `VIEWER` cannot).
  It is fetched when you press the button, shown until you hide it, switch case or leave the page, and never stored in the
  browser. Read it to the player or send it through a private channel; do not write it into a note or message (they
  are rejected when they contain a Ban ID).
- **Look up** a punishment by Ban ID with `POST /punishment/lookup`, which takes the Ban ID in the body
  (`MODERATOR`). A Ban ID never goes into a URL. The old hex short id (`#AB12CD`) still finds a punishment for staff, but the portal does
  not accept it.
- A punishment without a Ban ID (right after an upgrade, until the backfill ran) says so in the panel.

### Leaked Ban IDs

If a Ban ID is posted in public, shared or found by someone else, mark it as leaked: *Mark as leaked*, choose a
reason (*Posted in public*, *Shared with others*, *Found by someone else*, *Other*). `MODERATOR` and above can do
it; marking it twice changes nothing.

What the mark does:

| When | Effect |
|------|--------|
| Before an appeal was filed | The player's sessions end and the right to appeal is **forfeited**: filing is refused until the Ban ID is reissued. |
| After an appeal was filed | The player's sessions end and the ticket is **frozen**: whoever logs in sees the facts only and can neither read nor write the conversation. Staff can still write on the case. |

Anyone could hold a leaked Ban ID, so after the mark the portal shows a login only the facts of the punishment,
never the conversation or the appeal. The Discord bot marks a leak by itself, with the reason
*Posted in public*, when it finds a current Ban ID in a scanned public channel, and removes the message. The
timeline then shows that the bot found it and whether the owner of the ticket or someone else posted it.

### Reissue

An `ADMIN` replaces the Ban ID with *Reissue Ban ID*, with a reason (*Not the player's fault* or *Other*):

- The old Ban ID stops working at once and the player's sessions end.
- A leak mark is cleared, so a forfeited right to appeal is back and a frozen ticket can be written to again.
- The new Ban ID is not shown by the reissue. Use *Reveal Ban ID* and pass it on to the player.
- The timeline records that a reissue happened and the generation numbers, never a Ban ID.

## 2. The conversation

The case page has a *Conversation* section: what the player wrote in the portal, what staff answered (in the panel
or in a Discord ticket), and the markers of the appeal. Every message is stored on the case when it is written, so
the case is the record whatever channel was used.

### What the player sees

- Staff replies written in the panel (`MODERATOR` and above, up to 2000 characters, no UUIDs, IP addresses or Ban
  IDs) are visible to the player. The composer has no visibility choice. Staff-internal text goes in a **note**,
  which the player never sees.
- The player sees staff only as *Moderation team*, never a name.
- The player sees their own filing and the decision as short markers. Notes, evidence, punishment events, staff-only
  messages and the stored protocol are never shown to them.
- A decision can carry a message to the player (*Message to the player* in the decision dialog). The internal
  decision note stays internal.
- A message edited by staff shows its new text to the player. A deleted message shows as removed, without text. Staff
  still see the last text, marked deleted. Only the author or an administrator can edit or delete a staff message; a
  player's words are changed only by redaction.

### Who wrote it, and "not verified"

A staff reply from the panel is **verified**: the backend takes the author from the signed-in token. A message the
player wrote carries no such proof, so the panel labels it *Written by the player, not verified* and shows it as
*Player (unverified)*, never with the verified badge. Treat what a player says about themselves or others as a claim.

Messages from Discord keep the same distinction: staff with the staff role **and** a linked Discord account are
verified; staff role only is *Unverified*; anyone else is shown as an external participant, and other bots as bots.
A player message on a `Waiting` case moves it to `In review`.

### Removing text (`ADMIN`)

*Remove text* on a message, or *Remove everything the player wrote*, removes the text and attachment data of every
version of those messages for good, for data protection. The entry, its time and its author kind stay, shown as
*Removed (data protection)*. Staff messages stay when the player's side is removed. Erasing a Discord participant or
anonymising a staff member has the same effect on their entries.

## 3. Transcript export (`ADMIN`)

*Transcript export* on the case page downloads the conversation and the lifecycle of the case, as JSON or text, in
order. Internal notes are left out unless you tick *Include internal notes*. Redacted entries export as markers,
without text. Use it for a complaint or a data-subject request.

Every export is recorded on the case (who, format, number of entries; no content). The file is not cached by the
browser or the server.

## 4. The stored appeal protocol

The case page section *Stored appeal protocol* holds the conversation of a decided appeal as a fixed record.

**When it is made.** The backend generates it from the stored case events only, never from Discord.

- For an appeal that used the portal and the panel: when the appeal is decided.
- For an appeal with a Discord ticket: when the bot closes the ticket and proves its transcript complete. A ticket
  whose transcript is not verified complete gets no protocol.

If storing fails, the decision stands and the backend retries (about every 5 minutes). Until it exists, the section
says that the decided appeal has no protocol yet.

**What it contains.** A readable text and a JSON file of the same record: every message with its time, channel,
author label, trust, visibility, edits and deletions (with the last text kept), attachment names, types and sizes
(no files, no links), and a completeness check at the end: number of entries, first and last sequence number and a
digest of the source events. It never contains a Ban ID, a Discord user id or an owner hash. Internal notes are
not part of it. The player appears as *Player*, verified staff under their display name, others as *Unverified
staff*, *Bot* or *External participant*.

**Versions.** The same conversation gives the same protocol. When it changes after the protocol was stored (a late
message or a correction, once it was quiet for a minute), a new version is stored and the earlier one is marked
*superseded*. The section lists them with *current* or *superseded*, the number of entries and the time stored.

**Reading it.** `VIEWER` and above can open the text and download text or JSON. Every opening and download is
written to the case. The file is an attachment, not cached.

**Redaction.** When text or author data in a protocol is removed (a message redaction, the erasure of the player's
messages or of a Discord participant, the anonymising of a staff member), every version of the affected appeals
becomes unreadable at once, in the same step, and the files are deleted. The backend then generates a new version
in which the removed entries are markers and erased authors are anonymous. If that fails, the redaction still
stands, the old versions stay unreadable and the backend finishes the new version later. Purged versions are not
listed, so for a short time a decided appeal can show the *no protocol yet* notice.

**The player's copy.** After an appeal is decided, the player can download a copy in the portal, as text or JSON.
It is not the stored file: it is generated when they ask, from exactly the entries the player can see in the
portal, so a staff-only entry cannot reach it. It names the player *You* and staff *Moderation team*. A player whose
Ban ID was marked leaked gets no copy.

## 5. Retention

> **Pending legal confirmation.** The periods below are the default behaviour of the system, not a confirmed
> retention policy. Do not state them to a player or an authority as final until legal has confirmed them.

- A conversation, its case and the stored protocol are kept as long as the punishment exists. Nothing deletes them
  because time has passed; an expired punishment keeps its history.
- Deleting the punishment record deletes its case and all messages with it.
- Removing text (section 2) is the way to erase personal content earlier; the protocol follows it as described in
  section 4.
- Files in a Discord ticket are not stored; only their metadata is. The Discord channel itself is deleted after the
  archive period set for the bot (see [`docs/discord-bot.md`](discord-bot.md)).
