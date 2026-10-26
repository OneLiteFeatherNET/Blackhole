package net.onelitefeather.blackhole.backend.profile;

import io.micronaut.jdbc.DataSourceResolver;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link ProfileSlots}. Every operation runs on one connection in a
 * short local transaction (no {@code @Transactional}, which is unusable in this codebase), locking
 * only the slot row it changes.
 */
@Singleton
public class JdbcProfileSlots implements ProfileSlots {

    private static final int MAX_ATTEMPTS = 3;

    private static final String ENSURE_PROFILE = """
            INSERT INTO punishment_profiles (owner, meta_data) VALUES (?, '{}'::jsonb)
            ON CONFLICT (owner) DO NOTHING
            """;
    private static final String LOCK_SLOT = """
            SELECT a.punishment_identifier, p.permanent
            FROM active_punishments a JOIN punishments p ON p.identifier = a.punishment_identifier
            WHERE a.owner = ? AND a.section_key = ?
            FOR UPDATE OF a
            """;
    private static final String REPLACE_SLOT = """
            UPDATE active_punishments SET punishment_identifier = ? WHERE owner = ? AND section_key = ?
            """;
    private static final String INSERT_SLOT = """
            INSERT INTO active_punishments (owner, section_key, punishment_identifier) VALUES (?, ?, ?)
            ON CONFLICT (owner, section_key) DO NOTHING
            """;
    private static final String DELETE_SLOT_IF = """
            DELETE FROM active_punishments WHERE owner = ? AND section_key = ? AND punishment_identifier = ?
            """;
    private static final String ADD_HISTORY = """
            INSERT INTO punishment_profiles_punishments (punishment_profile_entity_owner, history_identifier)
            VALUES (?, ?) ON CONFLICT (history_identifier) DO NOTHING
            """;
    private static final String MERGE_SESSION = """
            INSERT INTO punishment_profiles (owner, meta_data)
            VALUES (?, jsonb_build_object('sessionInfo', ?::jsonb))
            ON CONFLICT (owner) DO UPDATE SET meta_data = jsonb_set(
                coalesce(punishment_profiles.meta_data, '{}'::jsonb),
                '{sessionInfo}',
                coalesce(punishment_profiles.meta_data -> 'sessionInfo', '{}'::jsonb) || (EXCLUDED.meta_data -> 'sessionInfo'))
            """;

    private final DataSource dataSource;
    private final JsonMapper jsonMapper;

    public JdbcProfileSlots(DataSource dataSource, DataSourceResolver resolver, JsonMapper jsonMapper) {
        // The injected source is transaction-aware and refuses connections outside a transaction.
        this.dataSource = resolver.resolve(dataSource);
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Placement occupy(String owner, String sectionKey, String punishmentIdentifier) {
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Placement placement = occupyOnce(connection, owner, sectionKey, punishmentIdentifier);
                if (placement.occupied()) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return placement;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not occupy the active slot", e);
        }
    }

    private Placement occupyOnce(Connection connection, String owner, String sectionKey, String identifier) throws SQLException {
        boolean created = update(connection, ENSURE_PROFILE, owner) == 1;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String held = null;
            boolean heldPermanent = false;
            try (PreparedStatement lock = connection.prepareStatement(LOCK_SLOT)) {
                lock.setString(1, owner);
                lock.setString(2, sectionKey);
                try (ResultSet result = lock.executeQuery()) {
                    if (result.next()) {
                        held = result.getString(1);
                        heldPermanent = result.getBoolean(2);
                    }
                }
            }
            if (held == null) {
                if (update(connection, INSERT_SLOT, owner, sectionKey, identifier) == 1) {
                    return new Placement(true, created, Optional.empty());
                }
                continue; // somebody filled the slot between our lock attempt and the insert
            }
            if (heldPermanent) {
                return new Placement(false, created, Optional.empty());
            }
            update(connection, REPLACE_SLOT, identifier, owner, sectionKey);
            update(connection, ADD_HISTORY, owner, held);
            return new Placement(true, created, Optional.of(held));
        }
        throw new IllegalStateException("the active slot of " + sectionKey + " is contended; giving up");
    }

    @Override
    public boolean clearIf(String owner, String sectionKey, String punishmentIdentifier) {
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean cleared = update(connection, DELETE_SLOT_IF, owner, sectionKey, punishmentIdentifier) == 1;
                if (cleared) {
                    update(connection, ADD_HISTORY, owner, punishmentIdentifier);
                }
                connection.commit();
                return cleared;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not clear the active slot", e);
        }
    }

    @Override
    public void appendHistory(String owner, String punishmentIdentifier) {
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                update(connection, ENSURE_PROFILE, owner);
                update(connection, ADD_HISTORY, owner, punishmentIdentifier);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not add to the history", e);
        }
    }

    @Override
    public void mergeSessionInfo(String owner, Map<String, Object> fields) {
        String json;
        try {
            json = this.jsonMapper.writeValueAsString(fields);
        } catch (IOException e) {
            throw new IllegalArgumentException("session info is not serializable", e);
        }
        try (Connection connection = this.dataSource.getConnection()) {
            update(connection, MERGE_SESSION, owner, json);
        } catch (SQLException e) {
            throw new IllegalStateException("could not merge the session info", e);
        }
    }

    private static int update(Connection connection, String sql, String... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }
}
