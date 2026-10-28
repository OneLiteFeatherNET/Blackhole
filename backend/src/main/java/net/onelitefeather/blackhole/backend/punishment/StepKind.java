package net.onelitefeather.blackhole.backend.punishment;

/** What a template step does: a timed punishment, a kick (no duration), or a warning (no duration, never active). */
public enum StepKind {
    PUNISH,
    KICK,
    WARN
}
