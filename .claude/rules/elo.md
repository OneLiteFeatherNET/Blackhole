---
paths:
  - "backend/src/main/java/**/elo/**/*.java"
  - "backend/src/main/java/**/section/**/*.java"
---

# Per-section ELO engine

Scores are per punishment section (`elo_scores`, one row per owner and section; a missing row is the
section's base score). `EloService` is the only write path and writes with single atomic upserts
(`EloScoreWriter`), so concurrent deltas add up; do not reintroduce read-modify-write of the score
row. Thresholds, pardon score and the permanent reason live on the section (`/section`), not in
config. What stays in `application.yml` (`blackhole.elo.*`): decay, the chat toxicity parameters and
the section it scores in (`chat.section`), and the report deltas and category-to-section mapping.

Escalation is decided by an `EscalationPolicy` (default: the threshold policy). After a negative delta
a score below the section's permanent threshold with no active permanent punishment becomes a
permanent punishment applied by the system; the optional soft threshold keeps the crossing rule and
applies a temporary one. Staff only ever create temporary punishments from templates.

An ACTIONED report with a template costs the reported player only the template's step cost; the flat
`actioned-delta` is for an ACTIONED report without a template. A staff revoke does not refund the ELO
cost of a temporary punishment; a granted appeal that fully lifts it does (`APPEAL_REFUND`), and lifting
a permanent one resets the score to the pardon score (`PARDON_RESET`).

The default `ToxicityScorer` is `TermListToxicityScorer`: it normalises the message (`elo/toxicity`),
matches the configured word lists per category (`blackhole.elo.chat.toxicity.terms.*`) on word
boundaries, drops allow-listed phrases, and scores the highest matched category weight. The word lists
are configuration (env-var overridable), not code; the repo ships them empty. Mode
`blackhole.elo.chat.toxicity.mode` is `OFF` (scorer not called), `SHADOW` (metric and log only, no ELO
change) or `ENFORCE`. A scorer failure is fail-open: no delta, no flag, the request still answers.
