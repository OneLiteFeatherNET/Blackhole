package net.onelitefeather.blackhole.backend.inbox;

import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import java.util.UUID;
import java.time.Duration;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The controller only maps the service answer to an HTTP response; the source is a stub here. */
class InboxControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);
    private static final UUID CALLER = UUID.fromString("c0000000-0000-4000-8000-0000000000aa");
    private static final Actor ACTOR = new Actor(CALLER, null, ActorKind.HUMAN, false);

    private static InboxSource emptySource() {
        return new InboxSource() {
            @Override
            public List<InboxDTO.Item> oldest(InboxSection section, int limit) {
                return List.of();
            }

            @Override
            public long count(InboxSection section) {
                return 0;
            }

            @Override
            public List<LatestChange> latestChangesSince(long since) {
                return List.of();
            }
        };
    }

    private static InboxSource brokenSource() {
        return new InboxSource() {
            @Override
            public List<InboxDTO.Item> oldest(InboxSection section, int limit) {
                throw new InboxUnavailableException("down", null);
            }

            @Override
            public long count(InboxSection section) {
                throw new InboxUnavailableException("down", null);
            }

            @Override
            public List<LatestChange> latestChangesSince(long since) {
                throw new InboxUnavailableException("down", null);
            }
        };
    }

    @Test
    void aReadableInboxAnswers200WithTheServiceResponse() {
        InboxService service = new InboxService(emptySource(), CLOCK, Duration.ofHours(24));

        HttpResponse<InboxDTO> reply = new InboxController(service).summary(ACTOR);

        assertEquals(HttpStatus.OK, reply.getStatus());
        assertEquals(service.summary(CALLER), reply.body(), "the body is the service answer, unchanged");
    }

    @Test
    void anUnavailableInboxAnswers503WithTheErrorVariant() {
        HttpResponse<InboxDTO> reply = new InboxController(new InboxService(brokenSource(), CLOCK, Duration.ofHours(24))).summary(ACTOR);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, reply.getStatus());
        assertEquals(ErrorKind.UNAVAILABLE, ((InboxDTO.Error) reply.body()).kind());
    }
}
