package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** PostgreSQL implementation of {@link PublicAppealReader}. Status 6 is {@code INELIGIBLE}, stored as the enum's ordinal. */
@Singleton
public class JdbcPublicAppealReader implements PublicAppealReader {

    private static final String LIST = """
            SELECT identifier, status, created_at, updated_at, legacy_duplicate_open
            FROM appeals
            WHERE punishment_identifier = ? AND status <> ?
            ORDER BY created_at DESC, identifier DESC
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcPublicAppealReader(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    /** For a plain data source, such as a test's. */
    public JdbcPublicAppealReader(DataSource plainDataSource) {
        this.dataSource = plainDataSource;
    }

    @Override
    public List<Row> list(String punishmentIdentifier) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(LIST)) {
            statement.setString(1, punishmentIdentifier);
            statement.setInt(2, AppealStatus.INELIGIBLE.ordinal());
            try (ResultSet rows = statement.executeQuery()) {
                List<Row> appeals = new ArrayList<>();
                while (rows.next()) {
                    appeals.add(new Row(rows.getObject(1, UUID.class), AppealStatus.values()[rows.getInt(2)], rows.getLong(3), rows.getLong(4),
                            rows.getBoolean(5)));
                }
                return appeals;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the appeals of " + punishmentIdentifier, e);
        }
    }
}
