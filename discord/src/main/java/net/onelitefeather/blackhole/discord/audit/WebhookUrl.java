package net.onelitefeather.blackhole.discord.audit;

import java.util.regex.Pattern;

/**
 * The Discord webhook of the staff audit channel. The URL is a secret (its path carries the token), so it is only ever
 * checked here and handed to the transport; it is never printed, logged or put in an exception message.
 */
public final class WebhookUrl {

    private static final Pattern WEBHOOK = Pattern.compile(
            "https://(discord\\.com|discordapp\\.com)/api/webhooks/[0-9]+/[A-Za-z0-9_.-]+");

    private WebhookUrl() {
    }

    /** @return whether the value is an https webhook URL of Discord; a null or blank value is not */
    public static boolean isValid(String value) {
        return value != null && WEBHOOK.matcher(value).matches();
    }
}
