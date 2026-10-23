package net.onelitefeather.blackhole.backend.report.feedback;

import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.punishment.RedisTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes the reporter's owner hash on {@link RedisTopology#REPORT_FEEDBACK_CHANNEL}.
 */
@Singleton
public class RedisReportFeedbackNotifier implements ReportFeedbackNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisReportFeedbackNotifier.class);

    private final StatefulRedisConnection<String, String> connection;

    public RedisReportFeedbackNotifier(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public void queued(String reporterHash) {
        try {
            this.connection.sync().publish(RedisTopology.REPORT_FEEDBACK_CHANNEL, reporterHash);
        } catch (Exception e) {
            // Best effort: the message stays queued and is delivered at the reporter's next login.
            LOGGER.warn("Failed to announce the report feedback: {}", e.getMessage());
        }
    }
}
