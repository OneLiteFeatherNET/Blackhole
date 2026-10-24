package net.onelitefeather.blackhole.backend.clientrule;

import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.punishment.RedisTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes the changed rule key on {@link RedisTopology#CLIENT_RULE_CHANGED_CHANNEL}. */
@Singleton
public class RedisClientRuleChangeNotifier implements ClientRuleChangeNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisClientRuleChangeNotifier.class);

    private final StatefulRedisConnection<String, String> connection;

    public RedisClientRuleChangeNotifier(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public void changed(String ruleKey) {
        try {
            this.connection.sync().publish(RedisTopology.CLIENT_RULE_CHANGED_CHANNEL, ruleKey);
        } catch (Exception e) {
            LOGGER.error("Failed to publish the client rule change for {}: {}", ruleKey, e.getMessage());
        }
    }
}
