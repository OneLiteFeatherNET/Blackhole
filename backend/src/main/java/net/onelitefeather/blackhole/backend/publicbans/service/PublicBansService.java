package net.onelitefeather.blackhole.backend.publicbans.service;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.publicbans.PublicBanCursor;
import net.onelitefeather.blackhole.backend.publicbans.PublicBanDay;
import net.onelitefeather.blackhole.backend.publicbans.PublicBanReader;
import net.onelitefeather.blackhole.backend.publicbans.PublicBanReader.ListedRow;
import net.onelitefeather.blackhole.backend.publicbans.PublicBanReader.PageQuery;
import net.onelitefeather.blackhole.backend.publicbans.PublicBanReader.ReasonCount;
import net.onelitefeather.blackhole.backend.publicbans.PublicBanStatus;
import net.onelitefeather.blackhole.backend.publicbans.dto.PublicBansDTO;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The read side of the public ban list: validates the query, pages with the signed cursor, rounds the stored instants to UTC
 * days and derives the status from the clock. Expected failures are returned as {@link PublicBansDTO.Error}.
 */
@Singleton
public class PublicBansService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final String HASH_SCHEME = "blackhole-ban-list-v1";
    private static final Pattern ENTRY_HASH = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern PLAYER_HASH = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Set<String> KINDS = Set.of("NETWORK_BAN", "MUTE", "SERVER");

    private final PublicBanReader reader;
    private final PublicBanCursor cursors;
    private final Clock clock;
    private final Duration retention;
    private final int summaryMinCount;

    @Inject
    public PublicBansService(PublicBanReader reader, PublicBanCursor cursors, Clock clock,
                             @Value("${blackhole.public-bans.retention:P90D}") String retention,
                             @Value("${blackhole.public-bans.summary-min-count:5}") int summaryMinCount) {
        this.reader = reader;
        this.cursors = cursors;
        this.clock = clock;
        this.retention = Duration.parse(retention);
        this.summaryMinCount = summaryMinCount;
    }

    /** One page of the list. Every argument is the raw query text; {@code null} means absent. */
    public PublicBansDTO list(String limitText, String cursorText, String kind, String statusText, String playerHash) {
        List<Map<String, String>> errors = new ArrayList<>();
        int limit = limit(limitText, errors);
        if (kind != null && !KINDS.contains(kind)) {
            errors.add(fieldError("kind", "kind must be NETWORK_BAN, MUTE or SERVER"));
        }
        PublicBanStatus status = status(statusText, errors);
        String player = playerHash == null ? null : playerHash.toLowerCase(java.util.Locale.ROOT);
        if (playerHash != null && !PLAYER_HASH.matcher(playerHash).matches()) {
            errors.add(fieldError("playerHash", "playerHash must be 64 hexadecimal characters"));
            player = null;
        }
        if (!errors.isEmpty()) {
            return new PublicBansDTO.Error(400, "VALIDATION", "A query parameter is not valid; see errors.", errors);
        }
        PublicBanCursor.Position after = null;
        if (cursorText != null) {
            after = this.cursors.decode(cursorText).orElse(null);
            if (after == null) {
                return new PublicBansDTO.Error(400, "INVALID_CURSOR", "The cursor is not valid.", List.of());
            }
        }
        long now = this.clock.millis();
        List<ListedRow> rows = this.reader.page(new PageQuery(after, kind, status, player, now, this.retention.toMillis(), limit + 1));
        boolean more = rows.size() > limit;
        List<ListedRow> shown = more ? rows.subList(0, limit) : rows;
        List<PublicBansDTO.Entry> items = shown.stream().map(row -> entry(row, now)).toList();
        String next = null;
        if (more) {
            ListedRow last = shown.getLast();
            next = this.cursors.encode(last.issuedDay(), last.hash());
        }
        return new PublicBansDTO.Page(items, next);
    }

    /** One entry by its hash; a malformed, unknown and out-of-window hash are the same not-found answer. */
    public PublicBansDTO entry(String hash) {
        long now = this.clock.millis();
        Optional<ListedRow> row = ENTRY_HASH.matcher(hash).matches()
                ? this.reader.byHash(hash, now, this.retention.toMillis())
                : Optional.empty();
        return row.<PublicBansDTO>map(found -> entry(found, now)).orElseGet(PublicBansService::notFound);
    }

    /** The counts per reason and kind; a row below the minimum keeps its reason but hides its counts. */
    public PublicBansDTO summary() {
        long now = this.clock.millis();
        List<PublicBansDTO.SummaryRow> rows = this.reader.summary(now, this.retention.toMillis()).stream()
                .map(this::summaryRow)
                .toList();
        return new PublicBansDTO.Summary((int) this.retention.toDays(), rows);
    }

    private PublicBansDTO.SummaryRow summaryRow(ReasonCount count) {
        if (count.total() < this.summaryMinCount) {
            return new PublicBansDTO.SummaryRow(count.reason(), count.kind(), null, null, true);
        }
        return new PublicBansDTO.SummaryRow(count.reason(), count.kind(), count.active(), count.total(), false);
    }

    private static PublicBansDTO.Entry entry(ListedRow row, long now) {
        PublicBanStatus status = PublicBanStatus.at(row.expiresAt(), row.revokedAt(), now);
        String endedOn = switch (status) {
            case ACTIVE -> null;
            case REVOKED -> day(row.revokedAt());
            case EXPIRED -> day(row.expiresAt());
        };
        return new PublicBansDTO.Entry(row.hash(), row.playerHash(), HASH_SCHEME, row.reason(), row.kind(), status.name(),
                PublicBanDay.toDate(row.issuedDay()).toString(), row.expiresAt() == null,
                row.expiresAt() == null ? null : day(row.expiresAt()), endedOn);
    }

    private static String day(long epochMillis) {
        return PublicBanDay.toDate(PublicBanDay.of(epochMillis)).toString();
    }

    private static PublicBansDTO.Error notFound() {
        return new PublicBansDTO.Error(404, "NOT_FOUND", "No ban-list entry matches the request.", List.of());
    }

    private static int limit(String text, List<Map<String, String>> errors) {
        if (text == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(text.strip());
            if (limit >= 1 && limit <= MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        errors.add(fieldError("limit", "limit must be a whole number from 1 to " + MAX_LIMIT));
        return DEFAULT_LIMIT;
    }

    private static PublicBanStatus status(String text, List<Map<String, String>> errors) {
        if (text == null) {
            return null;
        }
        try {
            return PublicBanStatus.valueOf(text);
        } catch (IllegalArgumentException e) {
            errors.add(fieldError("status", "status must be ACTIVE, EXPIRED or REVOKED"));
            return null;
        }
    }

    private static Map<String, String> fieldError(String field, String message) {
        Map<String, String> error = new HashMap<>();
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}
