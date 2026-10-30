package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.casetimeline.CaseDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The join_guard_settings row against the real schema: seeded from the configuration once, then the stored row wins. */
@Tag("integration")
class JdbcGuardSettingsStoreTest extends PostgresIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T11:00:00Z"), ZoneOffset.UTC);
    private static final GuardSettings CONFIG = GuardSettings.of("STRICT", Duration.ofMinutes(20), "01:00-07:00", "Europe/Berlin",
            Duration.ofMinutes(5));

    private CaseDatabase db;

    @BeforeEach
    void givenAMigratedSchema() {
        this.db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("latest");
    }

    @Test
    void aMissingRowIsSeededFromTheConfiguredValuesWithoutAnAdministrator() {
        UnattendedSettings seeded = new JdbcGuardSettingsStore(this.db.dataSource, CONFIG, CLOCK).current();

        assertEquals(GuardLevel.STRICT, seeded.unattendedLevel(), "the seeded level must be the configured one");
        assertEquals(Duration.ofMinutes(20), seeded.absenceThreshold(), "the seeded threshold must be the configured one");
        assertEquals(NightWindow.parse("01:00-07:00"), seeded.nightWindow(), "the seeded window must be the configured one");
        assertEquals(ZoneId.of("Europe/Berlin"), seeded.zone(), "the seeded zone must be the configured one");
        assertNull(seeded.updatedByOid(), "the seeded row must name no administrator, so it is recognisable as the configured one");
    }

    @Test
    void aSavedRowWinsOverTheConfigurationOnTheNextRead() {
        JdbcGuardSettingsStore store = new JdbcGuardSettingsStore(this.db.dataSource, CONFIG, CLOCK);
        store.current();
        UnattendedSettings changed = new UnattendedSettings(GuardLevel.LOCKDOWN, Duration.ofMinutes(45), false,
                NightWindow.parse("23:00-02:00"), ZoneId.of("Europe/Berlin"), CLOCK.millis(), "00000000-0000-4000-8000-0000000000b1");

        store.save(changed);

        UnattendedSettings readBack = new JdbcGuardSettingsStore(this.db.dataSource, CONFIG, CLOCK).current();
        assertEquals(changed, readBack, "a later start with the same configuration must read the saved row, not seed again");
    }

    @Test
    void aSecondStoreWithDifferentConfigurationDoesNotOverwriteTheSeed() {
        new JdbcGuardSettingsStore(this.db.dataSource, CONFIG, CLOCK).current();
        GuardSettings other = GuardSettings.of("LOCKDOWN", Duration.ofMinutes(60), "02:00-03:00", "Europe/Paris", Duration.ofMinutes(5));

        UnattendedSettings read = new JdbcGuardSettingsStore(this.db.dataSource, other, CLOCK).current();

        assertEquals(Duration.ofMinutes(20), read.absenceThreshold(),
                "the row exists, so the second configuration must not seed over it: the configuration only seeds the first start");
    }
}
