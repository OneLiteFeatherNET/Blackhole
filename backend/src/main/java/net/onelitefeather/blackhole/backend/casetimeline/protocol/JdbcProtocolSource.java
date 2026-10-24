package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL implementation of {@link ProtocolSource}: plain reads of the appeal rows and the case events. */
@Singleton
public class JdbcProtocolSource implements ProtocolSource {

    private static final String SELECT = """
            SELECT a.identifier, a.punishment_identifier, p.owner, a.status, a.created_at, a.updated_at
            FROM appeals a JOIN punishments p ON p.identifier = a.punishment_identifier
            """;
    private static final String ONE = SELECT + " WHERE a.identifier = ?";
    private static final String OF_PUNISHMENT = SELECT + " WHERE a.punishment_identifier = ? ORDER BY a.created_at, a.identifier";

    private final DataSource dataSource;
    private final CaseReader reader;

    @Inject
    public JdbcProtocolSource(DataSource dataSource, DataSourceResolver resolver, CaseReader reader) {
        this.dataSource = resolver.resolve(dataSource);
        this.reader = reader;
    }

    /** For a plain data source, such as a test's. */
    public JdbcProtocolSource(DataSource plainDataSource, CaseReader reader) {
        this.dataSource = plainDataSource;
        this.reader = reader;
    }

    @Override
    public Optional<Appeal> appeal(UUID appealId) {
        return appeals(ONE, appealId).stream().findFirst();
    }

    @Override
    public List<Appeal> appealsOf(String punishmentIdentifier) {
        return appeals(OF_PUNISHMENT, punishmentIdentifier);
    }

    @Override
    public List<CaseReader.Entry> entries(String punishmentIdentifier) {
        return this.reader.timeline(new CaseReader.TimelineQuery(punishmentIdentifier, null, null, List.of(), null, null, null, true, Integer.MAX_VALUE));
    }

    private List<Appeal> appeals(String sql, Object parameter) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                List<Appeal> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new Appeal(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3),
                            AppealStatus.values()[rows.getInt(4)], rows.getLong(5), rows.getLong(6)));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the appeals for a protocol", e);
        }
    }
}
