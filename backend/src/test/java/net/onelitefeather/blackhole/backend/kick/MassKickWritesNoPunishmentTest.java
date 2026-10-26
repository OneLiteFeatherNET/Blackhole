package net.onelitefeather.blackhole.backend.kick;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A mass kick is one audited action: nothing per player is written. */
@Tag("integration")
class MassKickWritesNoPunishmentTest extends ApiIntegrationTest {

    private long count(String table) {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("could not count " + table, e);
        }
    }

    private Reply massKick(String key, String token) {
        return post("/kick/mass", Map.of("scope", "SERVER", "target", "Lobby-1", "reason", "maintenance"), token, Map.of("Idempotency-Key", key));
    }

    @Test
    void aMassKickWritesOneActionAndNoPunishmentCaseEventOrEloEvent() {
        long punishments = count("punishments");
        long caseEvents = count("case_event");
        long eloEvents = count("elo_events");
        long actions = count("kick_action");

        Reply reply = massKick("mass-1", this.jwt.admin());

        assertEquals(202, reply.status(), reply.body());
        assertEquals("MASS", reply.json().get("kind"));
        assertEquals(actions + 1, count("kick_action"), "one audited action");
        assertEquals(punishments, count("punishments"), "no punishment row");
        assertEquals(caseEvents, count("case_event"), "no case event");
        assertEquals(eloEvents, count("elo_events"), "no ELO event");
    }

    @Test
    void aRepeatedMassKickWritesNothingMore() {
        String admin = this.jwt.admin();
        massKick("mass-2", admin);
        long actions = count("kick_action");

        Reply repeat = massKick("mass-2", admin);

        assertEquals(true, repeat.json().get("repeated"));
        assertEquals(actions, count("kick_action"), "the repeat wrote nothing");
    }

    @Test
    void aMassKickNobodyAnsweredReadsPendingBeforeTheDeadline() {
        Reply accepted = massKick("mass-3", this.jwt.admin());

        Map<String, Object> read = get("/kick/" + accepted.json().get("id")).json();

        assertEquals("MASS", read.get("kind"));
        assertEquals("maintenance", read.get("reason"));
        assertEquals("PENDING", read.get("status"));
    }

    @Test
    void aModeratorMayNotMassKick() {
        assertEquals(403, massKick("mass-4", this.jwt.moderator()).status());
    }

    @Test
    void aMassKickOfTheWholeNetworkIs422() {
        Reply reply = post("/kick/mass", Map.of("scope", "NETWORK", "target", "x", "reason", "maintenance"), this.jwt.admin(),
                Map.of("Idempotency-Key", "mass-5"));

        assertEquals(422, reply.status(), reply.body());
    }
}
