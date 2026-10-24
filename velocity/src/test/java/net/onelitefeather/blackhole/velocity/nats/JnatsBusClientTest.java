package net.onelitefeather.blackhole.velocity.nats;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JnatsBusClientTest {

    private final List<String> events = new ArrayList<>();
    private final List<Runnable> connects = new ArrayList<>();

    private final BusClient.Listener listener = new BusClient.Listener() {
        @Override
        public void connected() {
            events.add("connected");
        }

        @Override
        public void disconnected() {
            events.add("disconnected");
        }

        @Override
        public void message(@NotNull String subject, byte @NotNull [] body) {
            events.add(subject);
        }
    };

    @Test
    void aMissingCredentialsFileLeavesTheProxyOnHttpAndDoesNotThrow(@TempDir Path dir) {
        JnatsBusClient client = new JnatsBusClient("wss://nats.example.test", dir.resolve("absent.creds").toString(),
                Duration.ofSeconds(2), connects::add);

        assertFalse(client.start(listener), "no credentials, no connection attempt");
        assertTrue(connects.isEmpty(), "nothing is even scheduled");
        assertTrue(events.isEmpty());
    }

    @Test
    void theConnectIsHandedToTheConnectorNeverRunOnTheCallingThread(@TempDir Path dir) throws Exception {
        Path creds = java.nio.file.Files.writeString(dir.resolve("blackhole.creds"), "not a real credential");
        JnatsBusClient client = new JnatsBusClient("wss://nats.example.test", creds.toString(), Duration.ofSeconds(2), connects::add);

        assertTrue(client.start(listener), "startup does not wait for NATS");
        assertEquals(1, connects.size(), "the blocking connect is only queued for another thread");
        client.close();
    }

    @Test
    void secretsInTheUrlAreNotLogged() {
        assertEquals("nats://nats.example.test:4222", JnatsBusClient.redact("nats://user:pass@nats.example.test:4222"));
        assertEquals("wss://nats.example.test", JnatsBusClient.redact("wss://nats.example.test"));
    }

    @Test
    void theSubscriptionsAreTheFiveSubjectGroupsOfTheProxyUser() {
        assertEquals(List.of("blackhole.events.punishment.*", "blackhole.events.appeal.resolved", "blackhole.events.section.changed",
                "blackhole.events.report.created", "blackhole.events.report.feedback"), Subjects.SUBSCRIPTIONS);
    }
}
