package net.onelitefeather.blackhole.discord.ticket;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The custom ids of the staff control buttons and decision modals: {@code staff:<action>:<ticketId>}. The id carries the action
 * and the ticket, never a Ban ID or a secret. A press is checked against the ticket of its channel before anything happens.
 */
public final class StaffControlIds {

    /** What a control does. */
    public enum Action {
        CLAIM("claim"), ACCEPT("accept"), REJECT("reject"), LOCK("lock"), UNLOCK("unlock"), CLOSE("close");

        private final String key;

        Action(String key) {
            this.key = key;
        }

        public String key() {
            return this.key;
        }
    }

    /**
     * A parsed id.
     *
     * @param action   what was pressed
     * @param ticketId the ticket it was made for
     */
    public record Press(@NotNull Action action, @NotNull String ticketId) {
    }

    private static final String PREFIX = "staff:";
    private static final Pattern TICKET_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private StaffControlIds() {
    }

    /** @return the custom id of an action on a ticket (also the id of its decision modal) */
    public static @NotNull String id(@NotNull Action action, @NotNull String ticketId) {
        return PREFIX + action.key() + ":" + ticketId;
    }

    /** @return whether the id belongs to the staff controls at all */
    public static boolean isStaffId(@Nullable String customId) {
        return customId != null && customId.startsWith(PREFIX);
    }

    /** @return the action and ticket of an id, empty for anything that is not a well formed staff id */
    public static @NotNull Optional<Press> parse(@Nullable String customId) {
        if (!isStaffId(customId)) {
            return Optional.empty();
        }
        String[] parts = customId.split(":", -1);
        if (parts.length != 3 || !TICKET_ID.matcher(parts[2]).matches()) {
            return Optional.empty();
        }
        return Arrays.stream(Action.values()).filter(action -> action.key().equals(parts[1])).findFirst()
                .map(action -> new Press(action, parts[2]));
    }
}
