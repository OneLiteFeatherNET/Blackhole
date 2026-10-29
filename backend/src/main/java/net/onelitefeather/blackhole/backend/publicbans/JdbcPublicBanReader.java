package net.onelitefeather.blackhole.backend.publicbans;

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

/**
 * The public ban list over the punishment table: the listing columns only, never a join, no transaction. The visibility
 * rule ({@link #LISTED}) and the status rules ({@link #ACTIVE}, {@link #EXPIRED}, {@link #REVOKED}) mirror
 * {@link PublicBanStatus}. The order is {@code public_issued_day DESC, public_hash DESC}, which the keyset cursor follows.
 */
@Singleton
public class JdbcPublicBanReader implements PublicBanReader {

    private static final String COLUMNS = "public_hash, player_hash_v1, public_reason, public_kind, public_issued_day, "
            + "public_expires_at, public_revoked_at";
    private static final String ACTIVE = "(public_revoked_at IS NULL AND (public_expires_at IS NULL OR public_expires_at > ?))";
    /** Active, or ended less than the retention ago: {@code COALESCE(revoked, expires) + retention > now}. */
    private static final String LISTED = "(public_hash IS NOT NULL AND (" + ACTIVE
            + " OR COALESCE(public_revoked_at, public_expires_at) + ? > ?))";
    private static final String EXPIRED = "(public_revoked_at IS NULL AND public_expires_at <= ?)";
    private static final String REVOKED = "public_revoked_at IS NOT NULL";

    private final DataSource dataSource;

    @Inject
    public JdbcPublicBanReader(DataSource dataSource, DataSourceResolver resolver) {
        this.dataSource = resolver.resolve(dataSource);
    }

    @Override
    public List<ListedRow> page(PageQuery query) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM punishments WHERE " + LISTED);
        List<Object> params = new ArrayList<>(List.of(query.now(), query.retentionMillis(), query.now()));
        if (query.kind() != null) {
            sql.append(" AND public_kind = ?");
            params.add(query.kind());
        }
        if (query.status() != null) {
            switch (query.status()) {
                case ACTIVE -> {
                    sql.append(" AND ").append(ACTIVE);
                    params.add(query.now());
                }
                case EXPIRED -> {
                    sql.append(" AND ").append(EXPIRED);
                    params.add(query.now());
                }
                case REVOKED -> sql.append(" AND ").append(REVOKED);
            }
        }
        if (query.playerHash() != null) {
            sql.append(" AND player_hash_v1 = ?");
            params.add(query.playerHash());
        }
        if (query.after() != null) {
            sql.append(" AND (public_issued_day < ? OR (public_issued_day = ? AND public_hash < ?))");
            params.add(query.after().day());
            params.add(query.after().day());
            params.add(query.after().hash());
        }
        sql.append(" ORDER BY public_issued_day DESC, public_hash DESC LIMIT ?");
        params.add(query.limit());
        return query(sql.toString(), params, rs -> listedRow(rs));
    }

    @Override
    public Optional<ListedRow> byHash(String hash, long now, long retentionMillis) {
        List<Object> params = List.of(hash, now, retentionMillis, now);
        List<ListedRow> rows = query("SELECT " + COLUMNS + " FROM punishments WHERE public_hash = ? AND " + LISTED, params,
                JdbcPublicBanReader::listedRow);
        return rows.stream().findFirst();
    }

    @Override
    public List<ReasonCount> summary(long now, long retentionMillis) {
        String sql = "SELECT public_reason, public_kind, COUNT(*) FILTER (WHERE " + ACTIVE + ") AS active_count, COUNT(*) AS total_count "
                + "FROM punishments WHERE " + LISTED + " GROUP BY public_reason, public_kind ORDER BY public_reason, public_kind";
        List<Object> params = List.of(now, now, retentionMillis, now);
        return query(sql, params, rs -> new ReasonCount(rs.getString("public_reason"), rs.getString("public_kind"),
                rs.getLong("active_count"), rs.getLong("total_count")));
    }

    private static ListedRow listedRow(ResultSet rs) throws SQLException {
        long expires = rs.getLong("public_expires_at");
        Long expiresAt = rs.wasNull() ? null : expires;
        long revoked = rs.getLong("public_revoked_at");
        Long revokedAt = rs.wasNull() ? null : revoked;
        return new ListedRow(rs.getString("public_hash"), rs.getString("player_hash_v1"), rs.getString("public_reason"),
                rs.getString("public_kind"), rs.getLong("public_issued_day"), expiresAt, revokedAt);
    }

    private <T> List<T> query(String sql, List<Object> params, RowMapper<T> mapper) {
        try (Connection connection = this.dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            List<T> rows = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the public ban list", e);
        }
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }
}
