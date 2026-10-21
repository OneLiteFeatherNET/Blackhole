package net.onelitefeather.blackhole.backend.dashboard;

import java.util.List;
import java.util.Map;

/**
 * Read-only queries behind the dashboard. A port so {@link DashboardService} can be tested without
 * a database; {@link JdbcDashboardSource} is the PostgreSQL implementation.
 */
public interface DashboardSource {

    /**
     * Active punishments per section key whose expiry (if any) is not before {@code now}, so a
     * punishment that expired but has not been swept yet is not counted. Sections without any are absent.
     */
    Map<String, Long> activePunishmentsBySection(long now);

    /** Reports that are OPEN or UNDER_REVIEW. */
    long openReports();

    /** Appeals that are SUBMITTED, ELIGIBLE_PENDING_REVIEW or IN_REVIEW. */
    long pendingAppeals();

    /** Automatic permanent punishments (no template) created at or after {@code since}, lifted ones included. */
    long escalationsSince(long since);

    /** The newest automatic permanent punishments, newest first, at most {@code limit}. */
    List<DashboardDTO.Escalation> latestEscalations(int limit);
}
