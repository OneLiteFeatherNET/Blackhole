package net.onelitefeather.blackhole.backend.events;

import java.util.regex.Pattern;

/**
 * Builds the NATS subject of an event: {@code blackhole.events.<eventType>}. Only the event type, a
 * fixed public vocabulary, may ever appear in a subject: no id, owner hash or other payload value
 * (the subject is visible to every subscriber and to the broker's metrics).
 */
public final class EventSubjects {

    /** The prefix of every event subject. */
    public static final String PREFIX = "blackhole.events.";

    /** The subject pattern the stream captures. */
    public static final String ALL = PREFIX + ">";

    /** Dot separated, non-empty tokens of ASCII letters, digits, '-' and '_': no whitespace and no wildcard. */
    private static final Pattern LEGAL_TYPE = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");

    private EventSubjects() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param eventType the event type, e.g. {@code punishment.created}
     * @return the subject to publish it on
     * @throws IllegalArgumentException if the type is empty, has an empty token or contains whitespace,
     *                                  {@code *}, {@code >} or any other character a subject token must not have
     */
    public static String forEventType(String eventType) {
        if (eventType == null || !LEGAL_TYPE.matcher(eventType).matches()) {
            throw new IllegalArgumentException("Not a legal event type for a NATS subject: " + eventType);
        }
        return PREFIX + eventType;
    }
}
