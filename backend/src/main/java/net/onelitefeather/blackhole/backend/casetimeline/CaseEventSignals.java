package net.onelitefeather.blackhole.backend.casetimeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Signals an event that a write has just appended, for the writers whose statement does not return the event's seq (the Discord
 * ticket lifecycle). The event is found by its dedupe key, which is unique by construction. A failed lookup is logged and never fails
 * the write: a lost signal costs only latency.
 */
public final class CaseEventSignals {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaseEventSignals.class);
    private static final String BY_DEDUPE_KEY = "SELECT punishment_identifier, seq, type FROM case_event WHERE dedupe_key = ?";

    private CaseEventSignals() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** Signals the event stored under {@code dedupeKey}, if there is one. */
    public static void byDedupeKey(DataSource dataSource, CaseChangeNotifier notifier, String dedupeKey) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(BY_DEDUPE_KEY)) {
            statement.setString(1, dedupeKey);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    notifier.appended(rows.getString(1), rows.getLong(2), rows.getString(3));
                }
            }
        } catch (SQLException | RuntimeException e) {
            LOGGER.warn("The live signal of a case event could not be looked up; clients catch up by polling", e);
        }
    }
}
