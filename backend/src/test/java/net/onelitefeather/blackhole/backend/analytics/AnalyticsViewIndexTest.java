package net.onelitefeather.blackhole.backend.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsViewIndexTest extends AnalyticsDatabaseTest {

    @Test
    void everyViewHasAUniqueIndexForConcurrentRefresh() {
        for (String view : AnalyticsViews.ALL) {
            List<Object> unique = this.db.column("SELECT i.indisunique FROM pg_index i "
                    + "WHERE i.indrelid = CAST(? AS regclass) AND i.indisunique", "analytics." + view);

            assertTrue(!unique.isEmpty(), "view " + view + " needs a unique index for REFRESH ... CONCURRENTLY");
        }
    }

    @Test
    void noColumnOfAUniqueIndexHoldsNullAfterARefreshOfRowsWithNulls() {
        // Rows with missing grouping values: a report without category, status or reporter, and a punishment without a
        // template or a source. Each must land in a sentinel group, never in a NULL key of the unique index.
        this.db.execute("INSERT INTO reports (identifier, category, status, reporter_hash, created_at, updated_at) "
                + "VALUES (gen_random_uuid(), NULL, NULL, NULL, ?, ?)", DAY, DAY + HOUR);
        insertPunishment("p-null", "PUNISHMENT", false, null, null, DAY);
        insertEloScore(OWNER_A, "network", 120);
        refreshAllViews();

        for (String view : AnalyticsViews.ALL) {
            List<Object> indexed = this.db.column("SELECT a.attname FROM pg_index i "
                    + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                    + "WHERE i.indrelid = CAST(? AS regclass) AND i.indisunique", "analytics." + view);
            for (Object column : indexed) {
                List<Object> nulls = this.db.column("SELECT count(*) FROM analytics." + view + " WHERE " + column + " IS NULL");

                assertEquals(List.of(0L), nulls, "column " + column + " of " + view + " is part of a unique index and must not be NULL");
            }
        }
    }
}
