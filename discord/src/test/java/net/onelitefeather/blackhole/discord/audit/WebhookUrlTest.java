package net.onelitefeather.blackhole.discord.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookUrlTest {

    @Test
    void aDiscordComWebhookIsAccepted() {
        assertTrue(WebhookUrl.isValid("https://discord.com/api/webhooks/123456789012345678/abcDEF_-.123"));
    }

    @Test
    void aDiscordAppComWebhookIsAccepted() {
        assertTrue(WebhookUrl.isValid("https://discordapp.com/api/webhooks/123456789012345678/abcDEF_-.123"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://discord.com/api/webhooks/123456789012345678/abc",
            "https://example.test/api/webhooks/123456789012345678/abc",
            "https://discord.com.example.test/api/webhooks/123456789012345678/abc",
            "https://discord.com/api/webhooks/123456789012345678/",
            "https://discord.com/api/webhooks/not-a-number/abc",
            "https://discord.com/api/v10/channels/123456789012345678/messages",
            "https://discord.com/api/webhooks/123456789012345678/abc?wait=true",
            " https://discord.com/api/webhooks/123456789012345678/abc"
    })
    void anyOtherUrlIsRefused(String value) {
        assertFalse(WebhookUrl.isValid(value), "refused: " + value.replaceAll("/[^/]*$", "/<token>"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void aMissingUrlIsRefusedByTheValidator(String value) {
        assertFalse(WebhookUrl.isValid(value));
    }
}
