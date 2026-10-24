package net.onelitefeather.blackhole.backend.publicapi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live streams over real HTTP. Each wait is bounded (a frame that never comes fails the test), nothing sleeps, and the keep-alive
 * interval (15 seconds) is covered on virtual time by {@link LiveHubTest}.
 */
@Tag("integration")
class LiveStreamApiIntegrationTest extends PublicApiTestBase {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final java.net.http.HttpClient CLIENT = java.net.http.HttpClient.newHttpClient();

    private final List<Stream> open = new ArrayList<>();

    /** One open stream: a reader thread turns the lines into events; {@link #next} waits (bounded) for the next one. */
    private final class Stream implements AutoCloseable {

        private final BlockingQueue<List<String>> events = new LinkedBlockingQueue<>();
        private final HttpResponse<InputStream> response;
        private final int status;

        Stream(HttpResponse<InputStream> response) {
            this.response = response;
            this.status = response.statusCode();
            Thread pump = new Thread(this::pump, "sse-test-reader");
            pump.setDaemon(true);
            pump.start();
        }

        private void pump() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.response.body(), StandardCharsets.UTF_8))) {
                List<String> current = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (!current.isEmpty()) {
                            this.events.add(current);
                            current = new ArrayList<>();
                        }
                    } else {
                        current.add(line);
                    }
                }
            } catch (IOException closed) {
                // the stream was closed by the test or the server
            }
        }

        /** The very next block of lines, whatever it is. */
        List<String> nextBlock() {
            try {
                List<String> block = this.events.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                assertNotNull(block, "no block within " + WAIT);
                return block;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", interrupted);
            }
        }

        /** The next event that has the given name, skipping the others (keep-alives, for one). */
        List<String> next(String name) {
            long deadline = System.nanoTime() + WAIT.toNanos();
            try {
                while (System.nanoTime() < deadline) {
                    List<String> event = this.events.poll(Duration.ofMillis(200).toMillis(), TimeUnit.MILLISECONDS);
                    if (event != null && event.contains("event: " + name)) {
                        return event;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("no " + name + " event within " + WAIT);
        }

        @Override
        public void close() {
            try {
                this.response.body().close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    @AfterEach
    void closeStreams() {
        this.open.forEach(Stream::close);
    }

    private Stream open(String path, String token, String lastEventId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.server.getURL() + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        try {
            Stream stream = new Stream(CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofInputStream()));
            this.open.add(stream);
            return stream;
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("could not open " + path, e);
        }
    }

    private static String dataOf(List<String> event) {
        return event.stream().filter(line -> line.startsWith("data: ")).map(line -> line.substring("data: ".length())).findFirst().orElse("");
    }

    private static String idOf(List<String> event) {
        return event.stream().filter(line -> line.startsWith("id: ")).map(line -> line.substring("id: ".length())).findFirst().orElse(null);
    }

    private String staffMessage(String punishment, String text) {
        var posted = post("/case/" + punishment + "/messages", Map.of("text", text), this.jwt.moderator());
        assertEquals(200, posted.status(), posted.body());
        return (String) posted.json().get("messageId");
    }

    @Test
    void aPlayerStreamOpensWithTheRetryHintThenReadyWithTheSeq() {
        String punishment = punish("a1".repeat(64), "live-open");
        String token = sessionFor(punishment);

        Stream stream = open("/public/conversation/events", token, null);

        assertEquals(200, stream.status);
        assertTrue(stream.response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        assertEquals("no-store, no-transform", stream.response.headers().firstValue("Cache-Control").orElse(""));
        List<String> retry = stream.nextBlock();
        assertEquals(List.of("retry: 5000"), retry, "the first block is the retry hint alone, with no empty data line");
        List<String> ready = stream.next("ready");
        assertTrue(dataOf(ready).matches("\\{\"seq\":\\d+}"), "ready carries only the seq: " + dataOf(ready));
    }

    @Test
    void aStaffMessageReachesThePlayersStreamAsAChangedThatCarriesOnlyTheSeq() {
        String punishment = punish("a2".repeat(64), "live-changed");
        String token = sessionFor(punishment);
        Stream player = open("/public/conversation/events", token, null);
        player.next("ready");
        staffMessage(punishment, "We are looking into it.");

        List<String> changed = player.next("changed");

        assertTrue(idOf(changed) != null && dataOf(changed).equals("{\"seq\":" + idOf(changed) + "}"),
                "changed has id = seq and data {\"seq\":N} and nothing else: " + changed);
        assertFalse(changed.toString().contains("looking into"), "no content in an event");
    }

    @Test
    void aStaffNoteIsNotSentToThePlayersStream() {
        String punishment = punish("a3".repeat(64), "live-note");
        String token = sessionFor(punishment);
        Stream player = open("/public/conversation/events", token, null);
        player.next("ready");
        assertEquals(200, post("/case/" + punishment + "/notes", Map.of("text", "internal only"), this.jwt.moderator()).status());
        staffMessage(punishment, "This one the player may read.");
        Raw read = playerGet("/public/conversation", token);
        @SuppressWarnings("unchecked") List<Map<String, Object>> entries = (List<Map<String, Object>>) read.json().get("entries");
        long visibleSeq = ((Number) entries.getLast().get("seq")).longValue();

        List<String> first = player.next("changed");

        assertEquals(String.valueOf(visibleSeq), idOf(first), "the first change the player hears is the visible message, not the note before it");
    }

    @Test
    void aLastEventIdBehindTheCaseGetsAChangedRightAfterReady() {
        String punishment = punish("a4".repeat(64), "live-resume");
        String token = sessionFor(punishment);
        staffMessage(punishment, "one");
        staffMessage(punishment, "two");

        Stream player = open("/public/conversation/events", token, "0");

        List<String> ready = player.next("ready");
        List<String> changed = player.next("changed");
        assertTrue(Long.parseLong(dataOf(changed).replaceAll("\\D", "")) >= Long.parseLong(dataOf(ready).replaceAll("\\D", "")),
                "the resume carries the current seq");
        assertNotNull(idOf(changed));
    }

    @Test
    void aMissingOrExpiredSessionGetsA401AndNoStream() {
        String punishment = punish("a5".repeat(64), "live-401");
        assertEquals(401, open("/public/conversation/events", null, null).status);
        assertEquals(401, open("/public/conversation/events", "bhps_not-a-session", null).status);
        assertFalse(sessionFor(punishment).isEmpty(), "the same punishment still has a valid session");
    }

    @Test
    void aSecondStreamOfTheSessionReplacesTheFirst() {
        String punishment = punish("a6".repeat(64), "live-replace");
        String token = sessionFor(punishment);
        Stream first = open("/public/conversation/events", token, null);
        first.next("ready");

        Stream second = open("/public/conversation/events", token, null);

        first.next("replaced");
        second.next("ready");
    }

    @Test
    void aLeakedBanIdEndsThePlayersStreamWithExpired() {
        String punishment = punish("a7".repeat(64), "live-leak");
        String token = sessionFor(punishment);
        Stream player = open("/public/conversation/events", token, null);
        player.next("ready");

        markLeaked(punishment);

        player.next("expired");
    }

    @Test
    void theStaffStreamNeedsAViewerAndDeniesTheProxy() {
        String punishment = punish("a8".repeat(64), "live-staff");
        String path = "/case/" + punishment + "/events";

        Stream viewer = open(path, this.jwt.viewer(), null);
        assertEquals(200, viewer.status);
        assertEquals(403, open(path, this.jwt.proxy(), null).status, "the proxy has no rule on the case stream");
        assertEquals(401, open(path, null, null).status);
    }

    @Test
    void theStaffStreamSeesTheStaffChangeAsAChanged() {
        String punishment = punish("a9".repeat(64), "live-staff-change");
        Stream staff = open("/case/" + punishment + "/events", this.jwt.viewer(), null);
        staff.next("ready");

        staffMessage(punishment, "from the team");

        staff.next("changed");
    }
}
