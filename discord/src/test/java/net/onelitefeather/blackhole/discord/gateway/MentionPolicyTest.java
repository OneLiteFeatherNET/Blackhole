package net.onelitefeather.blackhole.discord.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionPolicyTest {

    @Test
    void the_allowed_mentions_of_relayed_posts_are_empty() {
        assertTrue(JdaGateway.noMentions().isEmpty(), "no users, no roles, no everyone");
    }
}
