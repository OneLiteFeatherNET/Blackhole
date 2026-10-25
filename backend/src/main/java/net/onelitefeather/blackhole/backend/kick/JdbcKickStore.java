package net.onelitefeather.blackhole.backend.kick;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link KickStore}. Each operation is one statement, so none needs a
 * transaction ({@code @Transactional} is unusable in this codebase).
 */
@Singleton
public class JdbcKickStore implements KickStore {

    private static final String INSERT_ACTION = """
            INSERT INTO kick_action (id, scope, target, reason, actor_oid, created_at, deadline_at)
            VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
            """;
    private static final String FIND_ACTION = """
            SELECT id, scope, target, reason, actor_oid, created_at, deadline_at FROM kick_action WHERE id = ?
            """;
    // One row per (kick, proxy); a second answer can only turn the flags on, so a repeat changes nothing.
    private static final String UPSERT_DELIVERY = """
            INSERT INTO kick_delivery (subject_id, proxy_id, found, moved, acked_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (subject_id, proxy_id) DO UPDATE SET
                found = kick_delivery.found OR EXCLUDED.found,
                moved = kick_delivery.moved OR EXCLUDED.moved
            """;
    private static final String DELIVERIES = """
            SELECT proxy_id, found, moved, acked_at FROM kick_delivery WHERE subject_id = ? ORDER BY proxy_id
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcKickStore(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcKickStore(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public boolean insertAction(Action action) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_ACTION)) {
            statement.setObject(1, action.id());
            statement.setString(2, action.scope().name());
            statement.setString(3, action.target());
            statement.setString(4, action.reason());
            statement.setObject(5, action.actorOid());
            statement.setLong(6, action.createdAt());
            statement.setLong(7, action.deadlineAt());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("could not store the kick action", e);
        }
    }

    @Override
    public Optional<Action> findAction(UUID id) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_ACTION)) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Action(rows.getObject("id", UUID.class), KickScope.valueOf(rows.getString("scope")),
                        rows.getString("target"), rows.getString("reason"), rows.getObject("actor_oid", UUID.class),
                        rows.getLong("created_at"), rows.getLong("deadline_at")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the kick action", e);
        }
    }

    @Override
    public void recordDelivery(UUID subjectId, Delivery delivery) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_DELIVERY)) {
            statement.setObject(1, subjectId);
            statement.setString(2, delivery.proxyId());
            statement.setBoolean(3, delivery.found());
            statement.setBoolean(4, delivery.moved());
            statement.setLong(5, delivery.ackedAt());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not store the kick delivery", e);
        }
    }

    @Override
    public List<Delivery> deliveries(UUID subjectId) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELIVERIES)) {
            statement.setObject(1, subjectId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Delivery> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new Delivery(rows.getString("proxy_id"), rows.getBoolean("found"), rows.getBoolean("moved"), rows.getLong("acked_at")));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the kick deliveries", e);
        }
    }
}
