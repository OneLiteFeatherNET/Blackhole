package net.onelitefeather.blackhole.analytics.mapping;

/**
 * The envelope is not stored. It is written to the dead-letter table with this reason, never dropped silently.
 *
 * @param reason why it was refused
 * @param detail which key or value caused it; never the value itself when that value is personal data
 */
public record DeadLetter(DeadLetterReason reason, String detail) implements MappingResult {
}
