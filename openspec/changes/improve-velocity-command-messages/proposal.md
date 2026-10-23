# Proposal

**Ships as:** `feat(velocity): improve staff command messages` (additive, text and interaction
only; no backend, API or schema change). Delivered as a stack of four small pull requests, each
titled `feat(velocity): ...` (see `tasks.md`).

## Why

Everything a staff member gets back from `/ban`, `/mute`, `/punish`, `/unban`, `/unmute`, `/elo`
and `/blackhole <user> info|history` is uncoloured text, usually one long line:

```
TheMeinerDev was punished in Network (step 1/2, ends in 5 hours 59 minutes). Score: 1000 -> 900.
```

The punishment screens players see (`punishment.screen.*`) are already coloured, so staff get
the worse experience. Errors state what went wrong but not what to do next, nothing is
clickable, the profile/history output is hard-coded English (not even in the translation
bundles), and there is no single place that defines the look, so every future message would
drift further apart.

## What Changes

- **Central style** for all staff-facing chat: a short prefix (`Blackhole »`) and semantic
  colours defined once as MiniMessage tags (`<bh:success>`, `<bh:player>`, ...) registered on the
  plugin's translation store, so the `.properties` texts stay readable and English/German stay
  consistent. Only glyphs the default Minecraft font renders (`✔ ✖ ⚠ » · → ▸ −`), no emoji.
- **Compact multi-line blocks** with a header line for the punish result, `/elo`, `info` and
  `history` instead of one long line.
- **Hover details** on the player and the punishment (reason, template key, short id, absolute
  end time in the configured `timeZone`, section thresholds where known).
- **Clickable actions**: the short id copies to the clipboard, the player name suggests
  `/elo <player>`, and an `[Undo]` / `[Rückgängig]` button *suggests* (never runs) the matching
  `/unban` / `/unmute`; the button is only shown if the sender has that permission.
- **Better errors and usage**: errors name the next step (unknown template lists matching keys of
  the right section, wrong section kind names the command that fits, unknown or offline target
  says how to find players), and wrong syntax prints a clear usage line.
- **Console senders** get the same content, coloured, with the hover essentials inlined and no
  buttons (see `design.md`).
- English and German texts for every new key; the key-parity test keeps guarding both bundles.
  The hard-coded English of `info`/`history` moves into the bundles.

Out of scope: the player-facing punishment screens and the "you have been muted" notice (already
styled), backend/API/client changes, new commands or permissions, the admin web UI, a runtime
config for colours or the prefix (tags are code, not configuration), further locales, and
letting the console issue punishments (they need an actor UUID, see open questions in
`design.md`). Nothing here is BREAKING for the backend, the generated client or other plugins.

GDPR: no new data is shown. Raw IPs are never rendered; owner hashes and player UUIDs appear
nowhere in the new messages. The short id (`#ABCD1234`) is the id players already quote.
