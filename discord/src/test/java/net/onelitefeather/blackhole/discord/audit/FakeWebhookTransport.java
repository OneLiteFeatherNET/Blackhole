package net.onelitefeather.blackhole.discord.audit;

import net.onelitefeather.blackhole.discord.audit.WebhookTransport.Answer;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Answers from a script: an {@link Answer} or an {@link IOException} per call; 204 once the script is empty. */
final class FakeWebhookTransport implements WebhookTransport {

    final List<String> bodies = new ArrayList<>();
    private final String url;
    private final Deque<Object> script;

    FakeWebhookTransport(String url, Object... script) {
        this.url = url;
        this.script = new ArrayDeque<>(List.of(script));
    }

    @Override
    public Answer post(String json) throws IOException {
        this.bodies.add(json);
        Object next = this.script.isEmpty() ? new Answer(204, "", null) : this.script.pollFirst();
        if (next instanceof IOException failure) {
            throw failure;
        }
        return (Answer) next;
    }

    /** The URL the fake pretends to talk to; a failure message carries it, so a test can check it is never logged. */
    String url() {
        return this.url;
    }
}
