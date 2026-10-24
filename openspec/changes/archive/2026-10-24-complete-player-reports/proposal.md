# Proposal

**Ships as:** four stacked pull requests, each one Conventional Commits type (see `tasks.md`):

1. `feat(velocity): report players with /report`
2. `feat(velocity): notify staff of new reports in game`
3. `feat(report): tell the reporter about the outcome` (the only backend, API and schema change)
4. `feat(web): add a report detail page`

## Why

The backend can record, list and resolve reports, and the admin UI lists them, but players have no
way to file one: there is no in-game command. Staff learn about a report only by opening the
queue, the reporter never hears what became of it, and the queue offers no page to judge a report
from (the evidence, the other reports against the same player and the player's current punishments
are spread over three screens).

## What Changes

- **`/report <player> <reason> [details]`** for every player (permission `blackhole.report`,
  granted unless explicitly denied). Tab completion for online players and the four reasons
  (`chat`, `cheating`, `griefing`, `other`, matching the backend categories). The reported
  player's recent chat is attached as `CHAT_LOG` evidence through the existing chat-capture path
  (when capture is enabled) and referenced by the report. Self-reports, unknown players and
  players holding `blackhole.report.bypass` are refused. The plugin adds **no** cooldown; the
  backend's existing report limiter answers HTTP 429, which is shown as a "please wait" message.
- **Staff notification in game**: online staff holding `blackhole.report.notify` get the
  reporter, the reported player, the reason and a short excerpt of the text, with a clickable
  "open" (suggests `/blackhole <player> info`) and "panel" (opens the report page when
  `panelUrl` is configured). The proxy that took the report publishes one notice on a Redis
  channel and every proxy, the publisher included, delivers it to its own staff once.
- **Outcome for the reporter**: resolving a report stores a small pending-feedback row for the
  reporter (the existing hashed owner) with an expiry and pokes the proxies. An online reporter
  is told at once ("action taken" / "no action", never the punishment); an offline reporter is
  told at the next login. The proxy fetches and acknowledges the pending feedback through new
  `PROXY` routes.
- **Report detail page** `/reports/:id` in the admin UI: pseudonymous reporter, reported player,
  category, text and time, the evidence (including the chat excerpt), the other reports against the
  player, the player's active punishments with case links, and the actions "Apply template" and
  "Dismiss" through the existing resolve route.
- English texts first, German second, everywhere.

## Out of scope

- Any cooldown, quota or other rate limit in the plugin or new in the backend (infra owns it).
- Names of players in backend data: the backend and the events carry hashes only, so the outcome
  message names the reason and the date of the report, not the reported player.
- Reporting from the web, Discord or the portal; changing the ELO effect of a resolution.
- A per-reporter history of reports in the UI.

## Impact

- Backend: table `report_feedback` (migration), `report.resolved` side effect, three `PROXY`
  routes under `/report/feedback`, one new client spec version.
- Velocity: new commands, Redis channels `blackhole.report.created` and
  `blackhole.report.feedback`, config key `panelUrl`, permissions `blackhole.report`,
  `blackhole.report.bypass`, `blackhole.report.notify`.
- Web: reports area only.
