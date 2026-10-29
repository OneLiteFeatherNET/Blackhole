package net.onelitefeather.blackhole.backend.publicbans.service;

import net.onelitefeather.blackhole.backend.publicbans.PublicBanCursor;
import net.onelitefeather.blackhole.backend.publicbans.PublicBanDay;
import net.onelitefeather.blackhole.backend.publicbans.PublicBanHasher;
import net.onelitefeather.blackhole.backend.publicbans.PublicBanReader;
import net.onelitefeather.blackhole.backend.publicbans.PublicBanStatus;
import net.onelitefeather.blackhole.backend.publicbans.dto.PublicBansDTO;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Service behaviour over an in-memory reader that applies the same visibility, status and order rules as the SQL. */
class PublicBansServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final long NOW_MS = NOW.toEpochMilli();
    private static final String VECTOR_PLAYER_HASH = "9efc1073aa30a316b4d4930dbf73bf852be666299041e930c9763de9f0c03029";
    private static final String HASH_A = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String HASH_B = "BBBBBBBBBBBBBBBBBBBBBB";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryReader reader = new InMemoryReader();
    private final PublicBanCursor cursors = new PublicBanCursor(new PublicBanHasher(Base64.getEncoder().encodeToString(new byte[32])));
    private final PublicBansService service = new PublicBansService(this.reader, this.cursors, this.clock, "P90D", 5);

    @Test
    void theDefaultPageHoldsFiftyEntriesAndPointsOnward() {
        seed(60, "NETWORK_BAN");

        PublicBansDTO.Page page = assertInstanceOf(PublicBansDTO.Page.class, this.service.list(null, null, null, null, null));

        assertEquals(50, page.items().size(), "the default limit is 50");
        assertNotNull(page.nextCursor(), "more entries follow, so a cursor is given");
    }

    @Test
    void followingTheCursorListsEveryEntryOnceAndTheLastPageHasNoCursor() {
        seed(5, "NETWORK_BAN");
        Set<String> seen = new HashSet<>();
        List<String> order = new ArrayList<>();
        String cursor = null;
        do {
            PublicBansDTO.Page page = assertInstanceOf(PublicBansDTO.Page.class, this.service.list("2", cursor, null, null, null));
            page.items().forEach(entry -> {
                order.add(entry.hash());
                seen.add(entry.hash());
            });
            cursor = page.nextCursor();
        } while (cursor != null);

        assertEquals(5, order.size(), "no entry is skipped");
        assertEquals(order.size(), seen.size(), "no entry is repeated");
    }

    @Test
    void aLimitOfZeroIsRefusedWithAnErrorNamingLimit() {
        assertLimitRefused("0");
    }

    @Test
    void aLimitAboveTwoHundredIsRefusedWithAnErrorNamingLimit() {
        assertLimitRefused("500");
    }

    @Test
    void aTamperedCursorIsAnInvalidCursorError() {
        seed(3, "NETWORK_BAN");
        String cursor = assertInstanceOf(PublicBansDTO.Page.class, this.service.list("1", null, null, null, null)).nextCursor();
        String tampered = (cursor.charAt(0) == 'A' ? "B" : "A") + cursor.substring(1);

        PublicBansDTO.Error error = assertInstanceOf(PublicBansDTO.Error.class, this.service.list("1", tampered, null, null, null));

        assertEquals("INVALID_CURSOR", error.code(), "a cursor that fails its signature is an invalid cursor");
        assertEquals(400, error.status(), "an invalid cursor is a bad request");
    }

    @Test
    void theKindFilterKeepsOnlyThatKind() {
        seed(2, "NETWORK_BAN");
        seed(2, "MUTE");

        PublicBansDTO.Page page = assertInstanceOf(PublicBansDTO.Page.class, this.service.list(null, null, "MUTE", null, null));

        assertEquals(2, page.items().size(), "two mutes are listed");
        assertTrue(page.items().stream().allMatch(entry -> "MUTE".equals(entry.kind())), "only mutes come back");
    }

    @Test
    void theStatusFilterKeepsOnlyRevokedEntries() {
        this.reader.add(row(HASH_A, "NETWORK_BAN", null, null, NOW_MS - 1_000));
        this.reader.add(row(HASH_B, "NETWORK_BAN", null, null, null));

        PublicBansDTO.Page page = assertInstanceOf(PublicBansDTO.Page.class, this.service.list(null, null, null, "REVOKED", null));

        assertEquals(List.of(HASH_A), page.items().stream().map(PublicBansDTO.Entry::hash).toList(), "only the revoked entry matches");
    }

    @Test
    void thePlayerHashFilterReturnsOnlyThatPlayersEntries() {
        this.reader.add(withPlayer(row(HASH_A, "NETWORK_BAN", null, null, null), VECTOR_PLAYER_HASH));
        this.reader.add(withPlayer(row(HASH_B, "NETWORK_BAN", null, null, null), "f".repeat(64)));

        PublicBansDTO.Page page = assertInstanceOf(PublicBansDTO.Page.class,
                this.service.list(null, null, null, null, VECTOR_PLAYER_HASH.toUpperCase()));

        assertEquals(List.of(HASH_A), page.items().stream().map(PublicBansDTO.Entry::hash).toList(),
                "an uppercase filter matches the lowercase stored value and returns only that player");
    }

    @Test
    void aPlayerHashOfTheWrongLengthIsRefusedWithoutEchoingIt() {
        PublicBansDTO.Error error = assertInstanceOf(PublicBansDTO.Error.class,
                this.service.list(null, null, null, null, VECTOR_PLAYER_HASH.substring(1)));

        assertEquals(List.of("playerHash"), fieldsOf(error), "the error names the filter");
        assertFalse(error.detail().contains(VECTOR_PLAYER_HASH.substring(1)), "the value is not echoed");
    }

    @Test
    void aPlayerHashWithANonHexCharacterIsRefused() {
        PublicBansDTO.Error error = assertInstanceOf(PublicBansDTO.Error.class,
                this.service.list(null, null, null, null, "g" + VECTOR_PLAYER_HASH.substring(1)));

        assertEquals(List.of("playerHash"), fieldsOf(error), "a character outside hex is refused");
    }

    @Test
    void anUnknownPlayerHashIsAnEmptyPageWithoutACursor() {
        PublicBansDTO.Page page = assertInstanceOf(PublicBansDTO.Page.class,
                this.service.list(null, null, null, null, VECTOR_PLAYER_HASH));

        assertTrue(page.items().isEmpty(), "no listed entry belongs to that player");
        assertNull(page.nextCursor(), "an empty page has no next cursor");
    }

    @Test
    void anUnknownHashAndAMalformedHashGiveTheSameNotFound() {
        seed(1, "NETWORK_BAN");

        PublicBansDTO unknown = this.service.entry("zzzzzzzzzzzzzzzzzzzzzz");
        PublicBansDTO malformed = this.service.entry("short");

        assertEquals(unknown, malformed, "an unknown and a malformed hash answer the same body");
        assertEquals(404, assertInstanceOf(PublicBansDTO.Error.class, unknown).status(), "both are not found");
    }

    @Test
    void anEntryOutsideTheRetentionWindowIsNotFound() {
        this.reader.add(row(HASH_A, "NETWORK_BAN", NOW_MS - Duration.ofDays(100).toMillis(), NOW_MS - Duration.ofDays(91).toMillis(), null));

        assertInstanceOf(PublicBansDTO.Error.class, this.service.entry(HASH_A), "an entry that ended 91 days ago is not answered");
    }

    @Test
    void anEntryThatExpiredBeforeTheSweepIsExpiredWithItsEndDay() {
        long expiresAt = NOW_MS - Duration.ofHours(1).toMillis();
        this.reader.add(row(HASH_A, "NETWORK_BAN", NOW_MS - Duration.ofDays(2).toMillis(), expiresAt, null));

        PublicBansDTO.Entry entry = assertInstanceOf(PublicBansDTO.Entry.class, this.service.entry(HASH_A));

        assertEquals("EXPIRED", entry.status(), "the status follows the expiry instant, not the sweep");
        assertEquals(PublicBanDay.toDate(PublicBanDay.of(expiresAt)).toString(), entry.endedOn(), "endedOn is the day of the expiry");
    }

    @Test
    void aRevokedEntryShowsItsStatusAndNoReason() {
        this.reader.add(row(HASH_A, "NETWORK_BAN", NOW_MS - Duration.ofDays(1).toMillis(), null, NOW_MS - 1_000));

        PublicBansDTO.Entry entry = assertInstanceOf(PublicBansDTO.Entry.class, this.service.entry(HASH_A));

        assertEquals("REVOKED", entry.status(), "a revoke shows as revoked");
        assertEquals("Cheating", entry.reason(), "the stored public reason is the only text");
    }

    @Test
    void aPermanentEntryIsActiveWithNoExpiryDay() {
        this.reader.add(row(HASH_A, "NETWORK_BAN", NOW_MS - 1_000, null, null));

        PublicBansDTO.Entry entry = assertInstanceOf(PublicBansDTO.Entry.class, this.service.entry(HASH_A));

        assertTrue(entry.permanent(), "no expiry means permanent");
        assertNull(entry.expiresOn(), "a permanent entry has no expiry day");
        assertNull(entry.endedOn(), "an active entry has no end day");
        assertEquals("ACTIVE", entry.status(), "a permanent entry is active until revoked");
    }

    @Test
    void theEntryCarriesTheHashSchemeEvenWithoutAPlayerHash() {
        this.reader.add(row(HASH_A, "NETWORK_BAN", NOW_MS - 1_000, null, null));

        PublicBansDTO.Entry entry = assertInstanceOf(PublicBansDTO.Entry.class, this.service.entry(HASH_A));

        assertNull(entry.playerHash(), "no UUID was sent, so no player hash");
        assertEquals("blackhole-ban-list-v1", entry.hashScheme(), "the scheme is named on every entry");
    }

    @Test
    void theIssuedDayIsTheUtcDayOfTheLastSecondOfTheDay() {
        long issuedAt = Instant.parse("2026-11-01T23:59:59Z").toEpochMilli();
        this.reader.add(row(HASH_A, "NETWORK_BAN", issuedAt, null, null));

        PublicBansDTO.Entry entry = assertInstanceOf(PublicBansDTO.Entry.class, this.service.entry(HASH_A));

        assertEquals("2026-11-01", entry.issuedOn(), "the day is UTC, not rounded up");
    }

    @Test
    void anEntryHasExactlyTheAllowedFields() {
        List<String> fields = java.util.Arrays.stream(PublicBansDTO.Entry.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();

        assertEquals(List.of("hash", "playerHash", "hashScheme", "reason", "kind", "status", "issuedOn", "permanent", "expiresOn",
                "endedOn"), fields, "no other field can reach a response");
    }

    @Test
    void aReasonCountBelowTheMinimumIsSuppressedButStillNamed() {
        seed(2, "NETWORK_BAN");

        PublicBansDTO.Summary summary = assertInstanceOf(PublicBansDTO.Summary.class, this.service.summary());
        PublicBansDTO.SummaryRow row = summary.reasons().getFirst();

        assertTrue(row.suppressed(), "two entries are below the minimum of five");
        assertNull(row.active(), "the active count is hidden");
        assertNull(row.total(), "the total is hidden");
        assertEquals(90, summary.windowDays(), "the window is the retention in days");
    }

    @Test
    void aReasonCountAtOrAboveTheMinimumShowsActiveAndTotal() {
        seed(8, "NETWORK_BAN");
        this.reader.add(row(HASH_A, "NETWORK_BAN", NOW_MS - Duration.ofDays(3).toMillis(), NOW_MS - 1_000, null));

        PublicBansDTO.SummaryRow row = assertInstanceOf(PublicBansDTO.Summary.class, this.service.summary()).reasons().getFirst();

        assertFalse(row.suppressed(), "eight entries are above the minimum");
        assertEquals(Long.valueOf(8), row.active(), "eight of the nine entries are active, the expired one is not");
        assertEquals(Long.valueOf(9), row.total(), "the total counts every listed entry of the reason");
    }

    private void assertLimitRefused(String limit) {
        PublicBansDTO.Error error = assertInstanceOf(PublicBansDTO.Error.class, this.service.list(limit, null, null, null, null));

        assertEquals(List.of("limit"), fieldsOf(error), "the error names limit");
    }

    private static List<String> fieldsOf(PublicBansDTO.Error error) {
        return error.errors().stream().map(entry -> entry.get("field")).toList();
    }

    /** Adds {@code count} active permanent entries of one reason and kind, issued one hour apart. */
    private void seed(int count, String kind) {
        IntStream.range(0, count).forEach(i -> this.reader.add(row(hashOf(kind, i), kind, NOW_MS - Duration.ofHours(i + 1).toMillis(), null, null)));
    }

    /** A 22-character hash that differs per kind and index. */
    private static String hashOf(String kind, int index) {
        return String.format("%s%021d", kind.substring(0, 1), index + 1);
    }

    private static InMemoryRow row(String hash, String kind, Long issuedAt, Long expiresAt, Long revokedAt) {
        return new InMemoryRow(hash, null, kind.equals("NETWORK_BAN") ? "Cheating" : "Spam", kind, issuedAt == null ? NOW_MS : issuedAt,
                expiresAt, revokedAt);
    }

    private static InMemoryRow withPlayer(InMemoryRow row, String playerHash) {
        return new InMemoryRow(row.hash(), playerHash, row.reason(), row.kind(), row.issuedAt(), row.expiresAt(), row.revokedAt());
    }

    /** The rows of one fixture, as the reader sees them: a plain value with the stored instants. */
    private record InMemoryRow(String hash, String playerHash, String reason, String kind, long issuedAt, Long expiresAt, Long revokedAt) {
    }

    /** The in-memory reader: the same visibility, status, filter and order rules as the SQL reader. */
    private final class InMemoryReader implements PublicBanReader {

        private final List<InMemoryRow> rows = new ArrayList<>();

        void add(InMemoryRow row) {
            this.rows.add(row);
        }

        @Override
        public List<ListedRow> page(PageQuery query) {
            Duration retention = Duration.ofMillis(query.retentionMillis());
            return this.rows.stream()
                    .filter(row -> PublicBanStatus.listed(row.expiresAt(), row.revokedAt(), query.now(), retention))
                    .filter(row -> query.kind() == null || query.kind().equals(row.kind()))
                    .filter(row -> query.status() == null || query.status() == PublicBanStatus.at(row.expiresAt(), row.revokedAt(), query.now()))
                    .filter(row -> query.playerHash() == null || query.playerHash().equals(row.playerHash()))
                    .filter(row -> query.after() == null || after(row, query.after()))
                    .sorted(Comparator.comparingLong((InMemoryRow row) -> PublicBanDay.of(row.issuedAt())).reversed()
                            .thenComparing(InMemoryRow::hash, Comparator.reverseOrder()))
                    .limit(query.limit())
                    .map(row -> new ListedRow(row.hash(), row.playerHash(), row.reason(), row.kind(), PublicBanDay.of(row.issuedAt()),
                            row.expiresAt(), row.revokedAt()))
                    .toList();
        }

        private static boolean after(InMemoryRow row, PublicBanCursor.Position position) {
            long day = PublicBanDay.of(row.issuedAt());
            return day < position.day() || (day == position.day() && row.hash().compareTo(position.hash()) < 0);
        }

        @Override
        public Optional<ListedRow> byHash(String hash, long now, long retentionMillis) {
            return this.rows.stream().filter(row -> row.hash().equals(hash))
                    .filter(row -> PublicBanStatus.listed(row.expiresAt(), row.revokedAt(), now, Duration.ofMillis(retentionMillis)))
                    .map(row -> new ListedRow(row.hash(), row.playerHash(), row.reason(), row.kind(), PublicBanDay.of(row.issuedAt()),
                            row.expiresAt(), row.revokedAt()))
                    .findFirst();
        }

        @Override
        public List<ReasonCount> summary(long now, long retentionMillis) {
            return this.rows.stream()
                    .filter(row -> PublicBanStatus.listed(row.expiresAt(), row.revokedAt(), now, Duration.ofMillis(retentionMillis)))
                    .collect(java.util.stream.Collectors.groupingBy(row -> row.reason() + "\u0001" + row.kind(),
                            java.util.TreeMap::new, java.util.stream.Collectors.toList()))
                    .values().stream()
                    .map(group -> {
                        InMemoryRow first = group.getFirst();
                        long active = group.stream().filter(row -> PublicBanStatus.at(row.expiresAt(), row.revokedAt(), now) == PublicBanStatus.ACTIVE).count();
                        return new ReasonCount(first.reason(), first.kind(), active, group.size());
                    })
                    .toList();
        }
    }
}
