package net.onelitefeather.blackhole.analytics;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsSinkModuleTest {

    // The NATS and Iceberg beans do not exist yet (task group 3). The context must start on its own:
    // no broker, catalog or bucket is contacted while the beans are being wired.
    @Test
    void applicationContextStartsWithoutAnyExternalService() {
        try (ApplicationContext context = ApplicationContext.run(Environment.TEST)) {
            assertTrue(context.isRunning(), "the analytics-sink application context should start in the test environment");
        }
    }
}
