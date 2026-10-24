package net.onelitefeather.blackhole.backend.clientrule;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleHitDTO;
import net.onelitefeather.blackhole.backend.support.FakeClientRuleHitStore;
import net.onelitefeather.blackhole.backend.support.FakeClientRuleStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A hit says which rule matched, never what the client sent. The rule's own pattern is the closest
 * thing to a trait the backend knows, so it must not travel with a hit into a log line, an event or a
 * stored or returned hit. The proxy never sends the trait itself; there is no field for it.
 */
class ClientRuleNoLeakTest {

    private static final String OWNER = "ab".repeat(64);
    private static final String PATTERN = "zq-secret-channel:*";

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        this.previousLevel = this.root.getLevel();
        this.root.setLevel(Level.ALL);
        this.appender.start();
        this.root.addAppender(this.appender);
    }

    @AfterEach
    void releaseLogs() {
        this.root.detachAppender(this.appender);
        this.root.setLevel(this.previousLevel);
    }

    @Test
    void aHitOfEveryActionLeavesThePatternOutOfLogsEventsAndTheStoredAndReturnedHits() {
        FakeClientRuleStore rules = new FakeClientRuleStore();
        FakeClientRuleHitStore hits = new FakeClientRuleHitStore(rules);
        RecordingEventPublisher events = new RecordingEventPublisher();
        ClientRuleHitService service = new ClientRuleHitService(hits,
                (owner, template, rule) -> {
                    throw new IllegalStateException("backend down for " + rule);
                }, events, rules, new TestClock());
        rules.given(ClientRuleSpec.builder("notify-rule", "Notify", ClientRuleKind.CHANNEL).patterns(PATTERN).build());
        rules.given(ClientRuleSpec.builder("deny-rule", "Deny", ClientRuleKind.CHANNEL).patterns(PATTERN).action(ClientRuleAction.DENY).build());
        rules.given(ClientRuleSpec.builder("template-rule", "Template", ClientRuleKind.CHANNEL).patterns(PATTERN)
                .action(ClientRuleAction.TEMPLATE).template("cheating").acknowledged().build());

        StringBuilder returned = new StringBuilder();
        for (String rule : new String[]{"notify-rule", "deny-rule", "template-rule"}) {
            returned.append(service.record(new ClientRuleHitDTO.CreateRequest(rule, OWNER, "lobby-1", true, false)));
        }
        returned.append(service.ofRule("deny-rule", null)).append(service.ofOwner(OWNER, null));

        assertFalse(this.appender.list.isEmpty(), "precondition: the failing template logs a line");
        for (ILoggingEvent line : this.appender.list) {
            assertNoPattern(line.getFormattedMessage());
            assertNoPattern(String.valueOf(line.getThrowableProxy()));
        }
        assertEquals(3, events.eventsOfType("client-rule.hit").size(), "precondition: every hit published an event");
        events.events().forEach(event -> assertNoPattern(event.type() + event.payload()));
        hits.all().forEach(hit -> assertNoPattern(hit.toString()));
        assertNoPattern(returned.toString());
    }

    private static void assertNoPattern(String text) {
        assertFalse(text.contains("zq-secret-channel"), "the rule's pattern must not appear in: " + text);
    }
}
