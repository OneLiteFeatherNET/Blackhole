package net.onelitefeather.blackhole.analytics.mapping;

/** The envelope maps to a row of its family's table. */
public record Mapped(EventRow row) implements MappingResult {
}
