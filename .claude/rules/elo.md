---
paths:
  - "backend/src/main/java/**/elo/**/*.java"
---

# Dual-ELO engine

Baseline/soft/hard thresholds and chat-toxicity scoring parameters are all externally configured
(see `application.yml`'s `blackhole.elo.*`); crossing soft threshold triggers an automatic
temporary punishment, hard threshold a permanent one. The built-in `ToxicityScorer` is a
placeholder keyword matcher, explicitly meant to be swapped for a real classifier later — don't
harden or extend its keyword list as if it were production moderation logic.
