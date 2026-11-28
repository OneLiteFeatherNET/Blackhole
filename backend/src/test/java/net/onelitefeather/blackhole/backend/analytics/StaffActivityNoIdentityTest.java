package net.onelitefeather.blackhole.backend.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StaffActivityNoIdentityTest extends AnalyticsDatabaseTest {

    @Test
    void staffActivityReturnsTheNumberOfDistinctStaffAndNoStaffIdentifier() {
        insertPunishment("pz", "PUNISHMENT", false, STAFF_SOURCE, null, DAY);
        insertCase("pz");
        insertCaseEvent("pz", "APPENDED", DAY, staffOid(7));
        insertCaseEvent("pz", "APPENDED", DAY + 1000, staffOid(8));
        insertCaseEvent("pz", "APPENDED", DAY + 2000, staffOid(7));
        refreshAllViews();

        List<Object> columns = this.db.column("SELECT attname FROM pg_attribute WHERE attrelid = 'analytics.staff_activity_daily'::regclass "
                + "AND attnum > 0 AND NOT attisdropped");
        List<List<Object>> rows = this.db.rows("SELECT distinct_actors FROM analytics.staff_activity_daily");

        assertFalse(columns.contains("actor_oid"), "no staff OID is a column of the view");
        assertEquals(List.of(List.of(2L)), rows, "two different staff acted, so the view counts two");
    }
}
