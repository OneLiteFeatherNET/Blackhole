package net.onelitefeather.blackhole.backend.inbox;

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
        };
    }

    @Test
    void aReadableInboxAnswers200WithTheServiceResponse() {
        InboxService service = new InboxService(emptySource(), CLOCK);

        HttpResponse<InboxDTO> reply = new InboxController(service).summary();

        assertEquals(HttpStatus.OK, reply.getStatus());
        assertEquals(service.summary(), reply.body(), "the body is the service answer, unchanged");
    }

    @Test
    void anUnavailableInboxAnswers503WithTheErrorVariant() {
        HttpResponse<InboxDTO> reply = new InboxController(new InboxService(brokenSource(), CLOCK)).summary();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, reply.getStatus());
        assertEquals(ErrorKind.UNAVAILABLE, ((InboxDTO.Error) reply.body()).kind());
    }
}
