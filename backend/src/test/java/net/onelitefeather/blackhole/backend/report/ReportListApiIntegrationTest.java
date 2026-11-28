package net.onelitefeather.blackhole.backend.report;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The panel's report list: {@code GET /report} is open to VIEWER with or without the owner filter.
 * Runs the real application against the real containers; every test uses its own owner hashes.
 */
@Tag("integration")
class ReportListApiIntegrationTest extends ApiIntegrationTest {

    private static String owner(String seed) {
        return seed.repeat(64);
    }

    private String submit(String reporter, String reported) {
        Reply submitted = post("/report", Map.of("reporterHash", owner(reporter), "reportedHash", owner(reported),
                "category", "CHEATING", "metaData", Map.of()), this.jwt.proxy());
        assertEquals(200, submitted.status(), submitted.body());
        return (String) submitted.json().get("identifier");
    }

    @Test
    void listingReportsAsViewerWithoutAnOwnerFilterReturnsAPage() {
        String report = submit("a1", "a2");

        Reply reply = get("/report", this.jwt.viewer());

        assertEquals(200, reply.status(), "the owner filter is optional: " + reply.body());
        List<Map<String, Object>> content = castList(reply.json().get("content"));
        assertTrue(content.stream().anyMatch(item -> report.equals(item.get("identifier"))),
                "the submitted report is on the first page: " + reply.body());
    }

    @Test
    void listingReportsAsViewerWithAnOwnerFilterReturnsOnlyThatOwnersReports() {
        String mine = submit("b1", "b2");
        submit("b3", "b4");

        Reply reply = get("/report?reportedHash=" + owner("b2"), this.jwt.viewer());

        assertEquals(200, reply.status(), reply.body());
        List<Map<String, Object>> content = castList(reply.json().get("content"));
        assertTrue(content.stream().anyMatch(item -> mine.equals(item.get("identifier"))),
                "the report against the filtered owner is listed: " + reply.body());
        assertTrue(content.stream().allMatch(item -> owner("b2").equals(item.get("reportedHash"))),
                "only reports against the filtered owner are listed: " + reply.body());
        assertFalse(content.stream().anyMatch(item -> owner("b4").equals(item.get("reportedHash"))),
                "reports against other owners are not listed");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
