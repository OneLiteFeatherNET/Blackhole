# Design

## Context

Observed in `velocity/`:

- Texts are MiniMessage strings in `blackhole_en.properties` / `blackhole_de.properties`, loaded
  into a `MiniMessageTranslationStore` (`BlackholeVelocity.registerTranslations`) and used as
  `Component.translatable(key).arguments(Component.text(...))`, placeholders `<arg:N>`. The
  `punishment.screen.*` keys already use `<red>`, `<gray>`, `<white>`, `<yellow>`.
- Results are built in `PunishmentMessages.applied` (punish) and inline in `PardonCommand`,
  `EloCommand`; `PunishProfileComponents` builds info/history with hard-coded English and
  `NamedTextColor`, not from the bundles.
- Commands are Cloud annotated methods taking `CommandContext<Player>`, although the manager is
  built for `CommandSource`; a console cannot run them today. Permissions are checked with
  `Player.hasPermission` (e.g. `blackhole.pardon.permanent` in `PardonCommand`).
- `PunishService` returns sealed outcomes (`Applied`, `TemplateNotFound`, `WrongSection(key, kind)`,
  `Failed(status)`, and for revokes `NothingActive`, `UnknownSection`, `WrongSection`, ...) and has
  `suggest(typed, kinds)` for tab completion. `PunishEntryDTO` carries `shortId`, `section`,
  `permanent`, `createdAt`, the template (key, reason) and metadata with the expiry.
  `BlackholeConfig.getZone()` is the configured `timeZone`. A `Clock` is injected everywhere.
- `I18nBundlesTest` guards key parity, non-empty texts, placeholder parity and that keys used in
  code exist.

## Goals / Non-Goals

Goals: one definition of the look; readable multi-line staff output; hover and click that help
without ever acting on their own; errors that say what to do; console stays usable; everything
unit-testable without a proxy.

Non-goals: backend/API changes, restyling player-facing screens, runtime-configurable themes,
new commands or permissions, console-issued punishments.

## Decisions

### D1. Theme = MiniMessage tags in the `bh` namespace, registered on the translation store

`MiniMessageTranslationStore.create(key, miniMessage)` is given a `MiniMessage` whose
`TagResolver` is a new `BlackholeTheme` (`component/theme/`). Translations then read like this:

```
punishment.unban.success=<bh:prefix><bh:ok>Lifted the <bh:section><arg:1></bh:section> ban of <bh:player><arg:0></bh:player>.
```

Tags (styling tags wrap content, inserting tags stand alone):

| Tag | Kind | Result |
|---|---|---|
| `<bh:prefix>` | inserting | dark purple `Blackhole` + dark grey ` » ` |
| `<bh:ok>` / `<bh:err>` / `<bh:warn>` | inserting | green `✔ ` / red `✖ ` / yellow `⚠ ` |
| `<bh:success>` `<bh:error>` `<bh:warning>` `<bh:info>` | styling | green / red / yellow / grey text |
| `<bh:player>` | styling | white, bold |
| `<bh:section>` | styling | aqua |
| `<bh:duration>` | styling | yellow |
| `<bh:id>` | styling | gold (short ids, copyable) |
| `<bh:key>` | styling | light purple (template keys) |
| `<bh:up>` / `<bh:down>` | styling | green / red (score direction) |
| `<bh:muted>` / `<bh:sep>` | styling / inserting | dark grey text / ` · ` |

Why this over the alternatives:

- *Java helper that builds every component* (`Messages.success(...)`): keeps colours central but
  moves the wording out of the bundles, so translators edit code. Rejected.
- *Plain `<green>`/`<red>` in the properties*: no code, but colours are scattered over ~60 texts
  and DE/EN drift; changing a colour means editing every line. Rejected.
- *`<bh:...>` tags*: wording stays in the bundles, the look is one class, a German translator
  cannot pick a wrong colour, and the tags are unit-testable on their own. Chosen.

The existing `punishment.screen.*` keys stay untouched (out of scope) but may adopt the tags later.

Hover and click do **not** go into the translations. `<hover>`/`<click>` need component arguments
and make texts unreadable. Instead Java builds the *arguments*: `Fmt.player(name, mode)` returns
`Component.text(name)` with a click (`suggest_command /elo name`) and a hover; the translation
only wraps `<arg:0>` in `<bh:player>`. Colour (tag) and events (argument) are different style
properties and compose. Hover text itself is again translatable (`staff.hover.*`) so it follows
the staff member's locale.

### D2. Message factories take a `Mode` (RICH or PLAIN), chosen from the receiver

```java
enum Mode { RICH, PLAIN }                      // PLAIN for non-Player receivers
static Mode of(Audience receiver) { return receiver instanceof Player ? RICH : PLAIN; }
```

`StaffMessages` (new, replaces `PunishmentMessages.applied` and the inline code) builds each
message from already-resolved data (`PunishmentApplyResponse`, `SectionInfo`, `Clock`, `ZoneId`,
a `Permissions` view, `Mode`). It is pure: no proxy, no backend. Tests render with
`PlainTextComponentSerializer` and walk the component tree for `clickEvent()`/`hoverEvent()`.

### D3. Console (PLAIN mode)

Colours stay (consoles render them or strip them harmlessly). No hover, no click, no buttons.
What hover carries for players is written inline instead, as one extra detail line:

```
Blackhole » ✔ Punished TheMeinerDev in Network
  ▸ Step 1/2 · ends 2026-10-22 20:14 (Europe/Berlin), in 5 hours 59 minutes
  ▸ Score: 1000 → 900 (−100)
  ▸ #A1B2C3D4 · template spam_1 · reason: Spam
```

Today the commands are `Player`-only, so a console cannot issue them anyway. PR 4 widens only
the read-only commands (`/elo`, `info`, `history`) to `CommandSource` so the PLAIN path is real
and reachable; `/ban`, `/mute`, `/punish`, `/unban`, `/unmute` stay `Player`-only because the
backend `source` is the actor UUID (see open question 1). Their factories are still
Mode-aware and tested in PLAIN, so lifting that restriction later is a sender-type change only.

### D4. Layout rules

- Header line: prefix + status glyph + one sentence with the player. Detail lines: two spaces,
  `▸`, content, grey separators ` · `. One message component per command (a single
  `sendMessage`), so lines never interleave with other chat.
- Keep each line under ~55 characters in English at default chat width; details wrap instead of
  being cut. The undo button sits at the end of the header line.
- Escalation adds a final warning line (`⚠`, yellow), no second message.
- Evidence feedback (`punishment.evidence.*`) becomes a detail line of the punish block in
  the same message when the result is ready at that point; today it is a second message sent
  afterwards, so PR 2 keeps it a separate styled line (`▸` info/warning) to avoid delaying
  the result. It is styled with the same tags either way.

### D5. Hover / click rules

| Element | Hover | Click |
|---|---|---|
| Player name | "Click: show ELO" + section scores are *not* fetched | `suggest_command /elo <name>` |
| Punishment (the `Step n/m` detail) | reason, template key, short id, end (absolute, `timeZone`) or "permanent" | none |
| Short id `#A1B2C3D4` | "Click to copy" | `copy_to_clipboard` the id |
| `[Undo]` | the command that is suggested | `suggest_command /unban <name> <section>` or `/unmute ...` |
| Section name in `/elo` | soft threshold, permanent threshold | none |
| History/info entry | as "Punishment" | short id copies |

Rules: nothing runs a command (`suggest_command`, never `run_command`); `[Undo]` appears only
if the sender has `blackhole.unban` (BAN/SERVER sections) or `blackhole.unmute` (MUTE sections),
and for a permanent punishment also `blackhole.pardon.permanent`; the suggested command uses the
section key so a server/non-default section is undone correctly; no hover contains IPs, owner
hashes or raw UUIDs. Absolute times use `BlackholeConfig.getZone()` and the injected `Clock` is
only used for relative durations, never read from the system. `copy_to_clipboard` needs a
client of Minecraft 1.15+; older clients simply ignore the click.

### D6. Errors and usage

- Unknown template: `PunishService` gets a pure `closest(typed, kinds, limit=5)` (case-insensitive
  contains, then shared prefix, then edit distance <= 2) reusing the template list `suggest`
  already loads; the error line lists keys as clickable `suggest_command /ban <player> <key>` in
  RICH mode and as comma separated text in PLAIN mode.
- Wrong section kind: `WrongSection(key, kind)` already carries the kind; the message names the
  fitting command (`MUTE -> /mute`, `BAN/SERVER -> /ban`, else `/punish`).
- Target not found: `TargetParser` failure text becomes: "No player named X is online or known.
  Names are case-insensitive; tab-complete to see online players."
- Backend failure: one short error line plus "Try again; if it keeps failing check the backend";
  status codes stay in the log only (already logged).
- Usage: Cloud exception handlers for invalid syntax, argument parse errors and missing
  permission are registered once, rendering `Usage: /ban <player> <template>` from the Cloud
  syntax of the failed command, in the staff member's locale. Missing permission keeps showing
  the generic proxy message (not redefined here).

### D7. No new migrations

No backend change, hence no Flyway migration and no regenerated client or OpenAPI spec.

## Mock-ups

Colour annotations: `{g}` green, `{r}` red, `{y}` yellow, `{a}` aqua, `{w}` white bold,
`{p}` dark purple, `{d}` dark grey, `{gr}` grey, `{o}` gold. `[..]` marks a hover/click element.

**Punish result, temporary** (`/ban TheMeinerDev spam_1`)

Before:
```
TheMeinerDev was punished in Network (step 1/2, ends in 5 hours 59 minutes). Score: 1000 -> 900.
```
After (player):
```
{p}Blackhole{d} » {g}✔ {gr}Punished {w}[TheMeinerDev]{gr} in {a}Network{gr}.        {y}[Undo]
{d}  ▸ {gr}Step {w}1/2{d} · {gr}ends in {y}5 hours 59 minutes{gr}                      (hover: reason Spam, template spam_1,
{d}  ▸ {gr}Score: 1000 → 900 {r}(−100){gr}                                            id #A1B2C3D4, ends 22.10.2026 20:14)
{d}  ▸ {gr}ID {o}[#A1B2C3D4]{gr}                                                      (click: copy)
```
`[TheMeinerDev]` click suggests `/elo TheMeinerDev`; `[Undo]` click suggests `/unban TheMeinerDev network`
and is absent without `blackhole.unban`. German: `Blackhole » ✔ TheMeinerDev bestraft in Network.`,
`Stufe 1/2 · endet in 5 Stunden 59 Minuten`, `Punktestand: 1000 → 900 (−100)`, `[Rückgängig]`.

**Escalation to permanent** (the third offence; same block plus a warning line)
```
{p}Blackhole{d} » {g}✔ {gr}Punished {w}TheMeinerDev{gr} in {a}Network{gr}.          {y}[Undo]
{d}  ▸ {gr}Step {w}2/2{d} · {gr}ends in {y}7 days{gr}
{d}  ▸ {gr}Score: 900 → 700 {r}(−200)
{y}  ⚠ Now permanently punished in {a}Network{y} (score 700). Needs blackhole.pardon.permanent to undo.
```
The hint is shown only to senders lacking `blackhole.pardon.permanent`; `[Undo]` follows the
D5 permission rule for the permanent punishment that is now in force.

**Unban success** (`/unban TheMeinerDev`)

Before: `You have unbanned TheMeinerDev successfully.`
```
{p}Blackhole{d} » {g}✔ {gr}Lifted the {a}network{gr} ban of {w}[TheMeinerDev]{gr}.
```
Mute: `Blackhole » ✔ Lifted the chat mute of TheMeinerDev.` Nothing active:
```
{p}Blackhole{d} » {y}⚠ {gr}{w}TheMeinerDev{gr} has no active ban in {a}network{gr}. Check {y}/blackhole TheMeinerDev info
```

**Error: unknown template** (`/ban TheMeinerDev spamm`)

Before: `Punishment template "spamm" was not found.`
```
{p}Blackhole{d} » {r}✖ {gr}No ban template {y}spamm{gr} found.
{d}  ▸ {gr}Did you mean: {y}[spam_1]{gr}, {y}[spam_2]{gr}, {y}[spam_caps]{gr}?      (click: suggest /ban TheMeinerDev spam_1)
{d}  ▸ {gr}Press TAB after the player name to list all templates.
```
No match: `  ▸ No ban or server template contains "xyz". Press TAB to list them.` Wrong kind:
```
{p}Blackhole{d} » {r}✖ {gr}{y}chat_spam{gr} is a mute template.  Use {y}[/mute TheMeinerDev chat_spam]
```

**`/elo TheMeinerDev` block**

Before:
```
ELO scores of TheMeinerDev:
Network: 900 (soft threshold 800, permanent below 500)
```
After:
```
{p}Blackhole{d} » {gr}ELO of {w}TheMeinerDev
{d}  ▸ {a}[Network]{gr}   {w}900{gr}   soft 800 · perm. 500                 (hover on section: thresholds)
{d}  ▸ {a}[Chat]{gr}      {r}300{gr}   soft 600 · perm. 400   {r}✖ permanent
```
A score at or below the soft threshold is `{y}`, below the permanent threshold or permanently
punished `{r}`, otherwise `{g}`.

**History / info block** (`/blackhole TheMeinerDev history newest`)

Before: `TheMeinerDevs history:` then `- [network, temporary] Spam`
```
{p}Blackhole{d} » {gr}History of {w}TheMeinerDev{gr} ({w}3{gr} entries, newest first)
{d}  ▸ {o}[#A1B2C3D4]{d} · {a}network{d} · {y}temporary{d} · {gr}22.10.2026 14:15 · Spam
{d}  ▸ {o}[#9F00AA11]{d} · {a}network{d} · {r}lifted{d}    · {gr}01.09.2026 09:02 · Beleidigung
```
Info: header `Active punishments of TheMeinerDev`, one `▸` line per section, `No active
punishments` as `{g}✔`. The possessive bug ("TheMeinerDevs") disappears because the header no
longer builds a genitive.

**Usage line** (`/ban TheMeinerDev`)
```
{p}Blackhole{d} » {r}✖ {gr}Missing the template.
{d}  ▸ {gr}Usage: {y}/ban <player> <template>{gr} - ban with a ban or server template
{d}  ▸ {gr}Example: {y}/ban TheMeinerDev spam_1
```

## Risks / Trade-offs

- A new translation `MiniMessage` instance with a custom resolver must also be used for any
  existing key that uses plain tags; custom tags are additive, so the screens keep rendering.
  Mitigation: a PR 1 test renders every existing key through the new store.
- Hover thresholds for the punish result are not in `PunishmentApplyResponse` (only scores and
  step). We do not add an extra backend call to a latency-sensitive staff command; thresholds are
  on `/elo` hovers (open question 2).
- Click/hover support differs between client versions; all information a decision needs is also
  inline text or reachable via `/blackhole <user> info`.
- Translation length: German texts run longer; the layout rules wrap instead of truncating.
- Cloud exception handlers are global to the command manager; scope them to the Blackhole
  commands' failures only and leave others alone.

## Migration Plan

Pure plugin change, deployed with the plugin jar. No data, config or API change; old and new
plugin versions interoperate with the same backend. Roll back by redeploying the previous jar.
Bundles are replaced key by key; the superseded keys (`punishment.result.applied`, `elo.row`, ...)
are removed in the PR that stops using them, which the "keys used in code exist" test and a
new "no unused key" test keep honest.

## Open Questions

1. Should the console be able to run `/ban` etc.? That needs an actor identity for the backend's
   `source` (a fixed console UUID, e.g. the nil UUID, or a reserved marker). Deferred together
   with the known per-actor-identity gap; this change only makes the output console-ready.
2. Should the punish hover show section thresholds (needs either an extra `GET /elo` call or an
   additive field on `PunishmentApplyResponse`, i.e. a backend change and a client bump), or are
   thresholds on `/elo` enough? Default here: `/elo` only.
3. Prefix colours: dark purple `Blackhole` + dark grey `»` (proposed) or another brand colour?
4. Should the player-facing messages that staff trigger (`punishment.info.muted`) adopt the prefix
   and tags too? Default here: no, out of scope.
