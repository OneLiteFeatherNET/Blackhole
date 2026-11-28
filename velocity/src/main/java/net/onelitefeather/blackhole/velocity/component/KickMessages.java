package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.onelitefeather.blackhole.client.model.KickResponse;
import net.onelitefeather.blackhole.client.model.KickScope;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a staff member is told about a kick. English plain text, like the kick screen: the outcome of a
 * kick is short and carries no translatable structure. Every line starts with the Blackhole prefix.
 */
public final class KickMessages {

    private KickMessages() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** @return the line shown when the backend took the kick and the proxies are being told */
    public static @NotNull Component sent(@NotNull String subject) {
        return BlackholeTheme.prefixed(Component.text("Kicking " + subject + " ...", NamedTextColor.GRAY));
    }

    /**
     * @param subject the player's name, or for a mass kick the target
     * @return the line for the final status of a kick
     */
    public static @NotNull Component outcome(
            @NotNull KickResponse response, @NotNull String subject, @Nullable String where, boolean mass
    ) {
        return switch (response.getStatus()) {
            case DELIVERED -> mass
                    ? BlackholeTheme.prefixed(Component.text("Kicked everybody from " + subject + " (delivered by " + response.getDeliveredCount() + " proxy/proxies).", NamedTextColor.GREEN))
                    : BlackholeTheme.prefixed(Component.text("Kicked " + subject + where(where) + ".", NamedTextColor.GREEN));
            case NOT_ON_TARGET -> BlackholeTheme.prefixed(Component.text(subject + " is online but not on " + (where == null ? "the target" : where)
                    + ", so nothing was done. The kick is recorded.", NamedTextColor.YELLOW));
            case NOT_ONLINE -> BlackholeTheme.prefixed(Component.text("No proxy found " + subject + " online. The kick is recorded.", NamedTextColor.YELLOW));
            case NO_PROXY_ANSWER -> BlackholeTheme.prefixed(Component.text("No proxy answered the kick of " + subject + ".", NamedTextColor.RED));
            case PENDING -> timedOut(subject);
        };
    }

    /** @return the line shown when the status was still open at the local deadline */
    public static @NotNull Component timedOut(@NotNull String subject) {
        return BlackholeTheme.prefixed(Component.text("No proxy has answered the kick of " + subject + " yet. It may still be delivered.", NamedTextColor.YELLOW));
    }

    /** @return the line for a kick the backend refused, by its HTTP status */
    public static @NotNull Component failed(int httpStatus) {
        String why = switch (httpStatus) {
            case 400 -> "the reason is not valid (1 to 200 characters, no UUID, IP address or Ban ID)";
            case 403 -> "you are not allowed to do that";
            case 404 -> "the player is not known";
            case 409 -> "the target section is disabled";
            case 422 -> "the server or section is unknown";
            default -> "the backend could not be reached or failed (" + httpStatus + ")";
        };
        return BlackholeTheme.prefixed(Component.text("The kick was not sent: " + why + ".", NamedTextColor.RED));
    }

    /** @return the line for arguments that cannot be used */
    public static @NotNull Component invalid(@NotNull String message) {
        return BlackholeTheme.prefixed(Component.text(message, NamedTextColor.RED));
    }

    /** @return the subject of a kick for a player */
    public static @NotNull String subjectOf(@NotNull KickScope scope, @Nullable String target) {
        return target == null ? "the network" : scope.getValue().toLowerCase() + " " + target;
    }

    private static String where(@Nullable String where) {
        return where == null ? "" : " from " + where;
    }
}
