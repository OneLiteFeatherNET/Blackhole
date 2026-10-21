package net.onelitefeather.blackhole.backend.section;

import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.punishment.RedisTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes the changed section key on {@link RedisTopology#SECTION_CHANGED_CHANNEL}.
 */
@Singleton
public class RedisSectionChangeNotifier implements SectionChangeNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisSectionChangeNotifier.class);

    private final StatefulRedisConnection<String, String> connection;

    public RedisSectionChangeNotifier(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public void changed(String sectionKey) {
        try {
            this.connection.sync().publish(RedisTopology.SECTION_CHANGED_CHANNEL, sectionKey);
        } catch (Exception e) {
            LOGGER.error("Failed to publish the section change for {}: {}", sectionKey, e.getMessage());
        }
    }
}
