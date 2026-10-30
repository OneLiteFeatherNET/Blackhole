package net.onelitefeather.blackhole.velocity.sentry;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SentryScrubberTest {

    private static SentryEvent eventWithMessage(String text) {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setMessage(text);
        message.setFormatted(text);
        event.setMessage(message);
        return event;
    }

    @Test
    void aBanIdInTheMessageIsRemovedFromTheEventPassedThroughTheHook() {
        SentryEvent scrubbed = new SentryScrubber().scrub(eventWithMessage("Could not punish with ban #AB12CD34 today"), new Hint());

        assertFalse(scrubbed.getMessage().getFormatted().contains("AB12CD34"), "a Ban ID must never reach Sentry");
        assertEquals("Could not punish with ban [ban-id] today", scrubbed.getMessage().getFormatted(), "the rest of the message is kept");
    }

    @Test
    void aBanIdTypedWithASpaceAndInLowerCaseIsRemoved() {
        SentryEvent scrubbed = new SentryScrubber().scrub(eventWithMessage("ban # ab12cd34 failed"), new Hint());

        assertFalse(scrubbed.getMessage().getFormatted().contains("ab12cd34"), "a typed Ban ID is a Ban ID too");
    }

    @Test
    void aBanIdInAnExceptionValueIsRemoved() {
        SentryEvent event = new SentryEvent();
        SentryException exception = new SentryException();
        exception.setType("IllegalStateException");
        exception.setValue("reason rejected for #K7XQ2M9P");
        event.setExceptions(List.of(exception));

        SentryEvent scrubbed = new SentryScrubber().scrub(event, new Hint());

        assertEquals("reason rejected for [ban-id]", scrubbed.getExceptions().get(0).getValue(),
                "a Ban ID inside an exception value is removed");
    }

    @Test
    void ordinaryHashtagsAreKept() {
        SentryEvent scrubbed = new SentryScrubber().scrub(eventWithMessage("joined #lobby on proxy-1"), new Hint());

        assertEquals("joined #lobby on proxy-1", scrubbed.getMessage().getFormatted(), "only Ban ID shaped tokens are removed");
    }

    @Test
    void anAuthorizationHeaderIsRemovedFromTheRequest() {
        SentryEvent event = eventWithMessage("backend call failed");
        Request request = new Request();
        request.setUrl("https://blackhole.example/api/punish");
        request.setHeaders(Map.of("Authorization", "Bearer eyJhbGciOi.secret.token", "Accept", "application/json"));
        event.setRequest(request);

        SentryEvent scrubbed = new SentryScrubber().scrub(event, new Hint());

        assertFalse(scrubbed.getRequest().getHeaders().containsKey("Authorization"), "the Authorization header is never sent");
        assertEquals("application/json", scrubbed.getRequest().getHeaders().get("Accept"), "other headers are kept");
    }

    @Test
    void aBearerTokenInATextIsRemoved() {
        SentryEvent scrubbed = new SentryScrubber().scrub(
                eventWithMessage("token refused for Bearer eyJhbGciOi.secret.token"), new Hint());

        assertFalse(scrubbed.getMessage().getFormatted().contains("eyJhbGciOi"), "a bearer token must never reach Sentry");
    }

    @Test
    void aRegisteredSecretIsRemovedFromEveryText() {
        SentryScrubber scrubber = new SentryScrubber();
        scrubber.addSecret("s3cr3t-client-value");

        SentryEvent scrubbed = scrubber.scrub(eventWithMessage("auth failed with s3cr3t-client-value"), new Hint());

        assertFalse(scrubbed.getMessage().getFormatted().contains("s3cr3t-client-value"), "the client secret is never sent");
    }

    @Test
    void aSecretAssignmentInATextIsRemoved() {
        SentryEvent scrubbed = new SentryScrubber().scrub(
                eventWithMessage("config read client_secret=abc123 from disk"), new Hint());

        assertFalse(scrubbed.getMessage().getFormatted().contains("abc123"), "a key=value secret is never sent");
    }

    @Test
    void theHookLeavesTheEventWhereItCanBeSent() {
        SentryEvent scrubbed = new SentryScrubber().scrub(eventWithMessage("plain"), new Hint());

        assertNotNull(scrubbed, "the hook must never drop a plain event by returning null");
        assertNull(scrubbed.getRequest(), "an event without a request keeps having none");
    }
}
