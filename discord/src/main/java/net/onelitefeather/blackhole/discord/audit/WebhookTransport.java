package net.onelitefeather.blackhole.discord.audit;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/** One POST of a JSON body to the audit webhook. The webhook URL is bound by the implementation, never passed in. */
public interface WebhookTransport {

    /**
     * @param status     the HTTP status
     * @param body       the response body (may be empty)
     * @param retryAfter the {@code Retry-After} header, or null
     */
    record Answer(int status, String body, @Nullable String retryAfter) {
    }

    /**
     * @throws IOException when no answer arrived (network, timeout); the message never carries the URL
     */
    Answer post(String json) throws IOException;
}
