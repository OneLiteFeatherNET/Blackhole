package net.onelitefeather.blackhole.backend.guard;

import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link GuardSettingsStore}. The seed is an insert that does nothing when the row exists, so
 * two replicas that seed at the same moment both succeed and leave one set of values.
 */
@Singleton
public class JdbcGuardSettingsStore implements GuardSettingsStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcGuardSettingsStore.class);
    private static final String CURRENT = "SELECT unattended_level, absence_threshold_minutes, night_window_enabled, "
            + "night_window_start_minute, night_window_end_minute, time_zone, updated_at, updated_by_oid FROM join_guard_settings WHERE id = 1";
    private static final String SEED = "INSERT INTO join_guard_settings (id, unattended_level, absence_threshold_minutes, "
            + "night_window_enabled, night_window_start_minute, night_window_end_minute, time_zone, updated_at, updated_by_oid) "
            + "VALUES (1, ?, ?, ?, ?, ?, ?, ?, NULL) ON CONFLICT (id) DO NOTHING";
    private static final String SAVE = "INSERT INTO join_guard_settings (id, unattended_level, absence_threshold_minutes, "
            + "night_window_enabled, night_window_start_minute, night_window_end_minute, time_zone, updated_at, updated_by_oid) "
            + "VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET unattended_level = EXCLUDED.unattended_level, "
            + "absence_threshold_minutes = EXCLUDED.absence_threshold_minutes, night_window_enabled = EXCLUDED.night_window_enabled, "
            + "night_window_start_minute = EXCLUDED.night_window_start_minute, night_window_end_minute = EXCLUDED.night_window_end_minute, "
            + "time_zone = EXCLUDED.time_zone, updated_at = EXCLUDED.updated_at, updated_by_oid = EXCLUDED.updated_by_oid";

    private final DataSource dataSource;
    private final GuardSettings configuration;
    private final Clock clock;

    @Inject
    public JdbcGuardSettingsStore(DataSource dataSource, DataSourceResolver resolver, GuardSettings configuration, Clock clock) {
        this.dataSource = resolver.resolve(dataSource);
        this.configuration = configuration;
        this.clock = clock;
    }

    /** For a plain data source, such as a test's. */
    public JdbcGuardSettingsStore(DataSource plainDataSource, GuardSettings configuration, Clock clock) {
        this.dataSource = plainDataSource;
        this.configuration = configuration;
        this.clock = clock;
    }

    @Override
    public UnattendedSettings current() {
        Optional<UnattendedSettings> stored = read();
        if (stored.isPresent()) {
            return stored.get();
        }
        UnattendedSettings seeded = UnattendedSettings.seeded(this.configuration, this.clock.millis());
        GuardSettingsRules.refusalOf(seeded).ifPresent(refusal -> {
            throw new IllegalStateException("The configured join guard settings are refused: " + refusal);
        });
        if (insertSeed(seeded)) {
            LOGGER.info("Join guard settings seeded from the configuration: level {}, absence threshold {} minutes, night window {}-{} ({}), zone {}",
                    seeded.unattendedLevel(), seeded.absenceThreshold().toMinutes(), seeded.nightWindow().start(),
                    seeded.nightWindow().end(), seeded.nightWindowEnabled() ? "enabled" : "disabled", seeded.zone().getId());
        }
        return read().orElseThrow(() -> new IllegalStateException("The join guard settings could not be read after seeding"));
    }

    @Override
    public void save(UnattendedSettings settings) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SAVE)) {
            bindValues(statement, settings);
            statement.setString(8, settings.updatedByOid());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("The join guard settings could not be written", e);
        }
    }

    private Optional<UnattendedSettings> read() {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CURRENT);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return Optional.empty();
            }
            return Optional.of(new UnattendedSettings(
                    GuardLevel.valueOf(rows.getString(1)),
                    Duration.ofMinutes(rows.getInt(2)),
                    rows.getBoolean(3),
                    new NightWindow(minuteOfDay(rows.getInt(4)), minuteOfDay(rows.getInt(5))),
                    ZoneId.of(rows.getString(6)),
                    rows.getLong(7),
                    rows.getString(8)));
        } catch (SQLException e) {
            throw new IllegalStateException("The join guard settings could not be read", e);
        }
    }

    /** @return true when this call inserted the row; false when another replica had already seeded it */
    private boolean insertSeed(UnattendedSettings seeded) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SEED)) {
            bindValues(statement, seeded);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("The join guard settings could not be seeded", e);
        }
    }

    /** Binds parameters 1 to 7: the level, the threshold, the window and the zone, the change time. */
    private static void bindValues(PreparedStatement statement, UnattendedSettings settings) throws SQLException {
        statement.setString(1, settings.unattendedLevel().name());
        statement.setInt(2, (int) settings.absenceThreshold().toMinutes());
        statement.setBoolean(3, settings.nightWindowEnabled());
        statement.setInt(4, minutesOfDay(settings.nightWindow().start()));
        statement.setInt(5, minutesOfDay(settings.nightWindow().end()));
        statement.setString(6, settings.zone().getId());
        statement.setLong(7, settings.updatedAt());
    }

    private static int minutesOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static LocalTime minuteOfDay(int minutes) {
        return LocalTime.of(minutes / 60, minutes % 60);
    }
}
