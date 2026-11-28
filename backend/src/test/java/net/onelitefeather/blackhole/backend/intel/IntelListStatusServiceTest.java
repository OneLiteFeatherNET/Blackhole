package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.intel.dto.ListStatusDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryIntelRecordStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelListStatusServiceTest {

    private static final Duration INTERVAL = Duration.ofHours(6);
    private static final String KEY = "x4bnet-vpn";

    private final TestClock clock = new TestClock();
    private final InMemoryIntelRecordStore store = new InMemoryIntelRecordStore();

    private static IntelListSourceConfig source(String key) {
        IntelListSourceConfig config = new IntelListSourceConfig(key);
        config.setKind(IntelClass.VPN);
        config.setEnabled(true);
        config.setAttribution("X4BNet lists_vpn, copyright 2024 X4B (Mathew Heard)");
        config.setLicence("MIT");
        return config;
    }

    private IntelListStatusService service(String hostingAsns) {
        return new IntelListStatusService(this.store, List.of(source(KEY)), this.clock, INTERVAL, hostingAsns);
    }

    /** Seeds the status row a refresh would leave: the attempt is now, the success {@code successAgo} earlier or none. */
    private void seed(Duration successAgo, String error) {
        long now = this.clock.millis();
        this.store.putSourceStatus(new IntelSourceStatus(KEY, now, successAgo == null ? null : now - successAgo.toMillis(),
                error, 1200L, 3L));
    }

    private ListStatusDTO.Source only(IntelListStatusService service) {
        return service.listing().sources().getFirst();
    }

    @Test
    void aSourceWhoseLastSuccessIsRecentIsFresh() {
        seed(Duration.ofHours(1), null);

        assertEquals(IntelSourceHealth.FRESH, only(service("")).status(), "a success within one interval is fresh");
    }

    @Test
    void aSourceWhoseLastSuccessIsExactlyTwoIntervalsOldIsStillFresh() {
        seed(INTERVAL.multipliedBy(2), null);

        assertEquals(IntelSourceHealth.FRESH, only(service("")).status(), "two intervals is the freshness limit");
    }

    @Test
    void aSourceWhoseLastSuccessIsOlderThanTwoIntervalsIsStale() {
        seed(INTERVAL.multipliedBy(2).plusMinutes(1), null);

        assertEquals(IntelSourceHealth.STALE, only(service("")).status(), "a missed run plus one is stale");
    }

    @Test
    void aSourceWhoseLastRefreshReturnedAnErrorIsFailedEvenWithARecentSuccess() {
        seed(Duration.ofHours(1), "the list could not be downloaded");

        assertEquals(IntelSourceHealth.FAILED, only(service("")).status(), "the last refresh failed");
    }

    @Test
    void aSourceNeverRefreshedIsStaleWithZeroEntries() {
        ListStatusDTO.Source view = only(service(""));

        assertEquals(IntelSourceHealth.STALE, view.status(), "no success is stored, so the data is not fresh");
        assertNull(view.lastSuccessAt(), "no success time is shown");
        assertEquals(0L, view.entryCount(), "no entries are counted");
    }

    @Test
    void theEntryAndInvalidCountsComeFromTheStoredStatus() {
        seed(Duration.ofHours(1), null);

        ListStatusDTO.Source view = only(service(""));

        assertEquals(1200L, view.entryCount(), "the entry count is the stored one");
        assertEquals(3L, view.invalidCount(), "the invalid count is the stored one");
    }

    @Test
    void theErrorShowsNoUrlCredentials() {
        seed(null, "https://reader:hunter2@lists.example.org/vpn.txt: connect timed out");

        String shown = only(service("")).lastError();

        assertFalse(shown.contains("hunter2"), "the password in the URL is not shown");
        assertFalse(shown.contains("reader:"), "the user name in the URL is not shown");
        assertTrue(shown.contains("connect timed out"), "the reason is kept");
    }

    @Test
    void aLongErrorIsShortened() {
        seed(null, "x".repeat(500));

        assertTrue(only(service("")).lastError().length() <= 120, "the error is at most 120 characters");
    }

    @Test
    void theAttributionAndLicenceOfEachSourceAreShown() {
        seed(Duration.ofHours(1), null);

        ListStatusDTO.Source view = only(service(""));

        assertEquals("MIT", view.licence(), "the licence is shown");
        assertTrue(view.attribution().contains("Mathew Heard"), "the attribution is shown");
        assertEquals(IntelClass.VPN, view.kind(), "the kind is shown");
        assertTrue(view.enabled(), "the enabled flag is shown");
    }

    @Test
    void theHostingAsnsAreListedSortedAndWithoutBlanks() {
        assertEquals(List.of(13335L, 64496L), service("64496, 13335,").listing().hostingAsns(),
                "the configured ASNs are read-only, sorted and trimmed");
    }
}
