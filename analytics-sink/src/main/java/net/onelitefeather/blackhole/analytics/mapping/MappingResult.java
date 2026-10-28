package net.onelitefeather.blackhole.analytics.mapping;

/** Outcome of mapping one envelope: a row for its table, or a dead letter with the reason it was refused. */
public sealed interface MappingResult permits Mapped, DeadLetter {
}
