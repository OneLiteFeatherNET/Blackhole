package net.onelitefeather.blackhole.discord.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.discord.notify.NotificationHandler.Outcome;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Turns the backend's case events into hints for the {@link SyncEngine}. The events carry no text and no Discord id, and the
 * bot does not trust them for anything: a leak mark, a reissue, a new portal or panel message or a decision only means "read the
 * ticket's cursors now". A staff link that was removed means "reconcile channel access now". An event that is lost or arrives
 * twice changes nothing, because the poll finds the same state.
 */
public final class CaseEventHandler {

    /** The event types of the durable consumer {@code blackhole-discord-case}. */
    public static final String[] ROUTING_KEYS = {
            "case.message_added", "case.message_edited", "case.message_deleted", "case.message_redacted",
            "case.ban_id_leak_marked", "case.ban_id_reissued", "case.status_changed", "appeal.resolved",
            "staff-link.removed", "discord-link.removed"
    };

    private static final Set<String> SYNC = Set.of(
            "case.message_added", "case.message_edited", "case.message_deleted", "case.message_redacted",
            "case.ban_id_leak_marked", "case.ban_id_reissued", "case.status_changed", "appeal.resolved");
    private static final Set<String> ACCESS = Set.of("staff-link.removed", "discord-link.removed");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SyncEngine engine;
    private final Consumer<String> caseHook;

    public CaseEventHandler(@NotNull SyncEngine engine) {
        this(engine, punishment -> { });
    }

    /**
     * @param caseHook told the punishment identifier of every case event, so the staff control message of that case is drawn again
     */
    public CaseEventHandler(@NotNull SyncEngine engine, @NotNull Consumer<String> caseHook) {
        this.engine = engine;
        this.caseHook = caseHook;
    }

    /** Never throws; a body that is not an event is dropped. */
    public @NotNull Outcome onMessage(byte[] body) {
        String type;
        String punishmentIdentifier;
        try {
            JsonNode root = JSON.readTree(body);
            type = root == null ? "" : root.path("eventType").asText("");
            punishmentIdentifier = root == null ? null : root.path("payload").path("punishmentIdentifier").asText(null);
        } catch (IOException e) {
            return Outcome.DROP;
        }
        if (SYNC.contains(type)) {
            this.engine.hint();
            this.caseHook.accept(punishmentIdentifier);
        } else if (ACCESS.contains(type)) {
            this.engine.reconcileHint();
        } else {
            return Outcome.DROP;
        }
        return Outcome.DONE;
    }
}
