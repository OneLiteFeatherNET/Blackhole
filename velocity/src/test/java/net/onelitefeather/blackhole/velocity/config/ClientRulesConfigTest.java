package net.onelitefeather.blackhole.velocity.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRulesConfigTest {

    @Test
    void theDefaultsAreOnWithAFiveSecondSettleThreePlayersAndAFifteenMinuteHeartbeat() {
        ClientRulesConfig config = new BlackholeConfig().getClientRules();

        assertTrue(config.isEnabled());
        assertEquals(Duration.ofSeconds(5), config.getSettle());
        assertEquals(3, config.getCatalogue().getMinPlayers());
        assertEquals(Duration.ofMinutes(15), config.getCatalogue().getHeartbeat());
    }

    @Test
    void aConfigWrittenBeforeTheSettingExistedGetsTheDefaults() {
        BlackholeConfig config = new Gson().fromJson("{}", BlackholeConfig.class);

        assertTrue(config.getClientRules().isEnabled());
        assertEquals(3, config.getClientRules().getCatalogue().getMinPlayers());
    }

    @Test
    void theFileValuesAreRead() {
        BlackholeConfig config = new Gson().fromJson(
                "{\"clientRules\":{\"enabled\":false,\"settle\":\"PT10S\",\"catalogue\":{\"minPlayers\":5,\"heartbeat\":\"PT1H\"}}}", BlackholeConfig.class);

        assertFalse(config.getClientRules().isEnabled());
        assertEquals(Duration.ofSeconds(10), config.getClientRules().getSettle());
        assertEquals(5, config.getClientRules().getCatalogue().getMinPlayers());
        assertEquals(Duration.ofHours(1), config.getClientRules().getCatalogue().getHeartbeat());
    }

    @Test
    void anUnreadableOrTooSmallValueFallsBackToTheDefault() {
        BlackholeConfig config = new Gson().fromJson(
                "{\"clientRules\":{\"settle\":\"soon\",\"catalogue\":{\"minPlayers\":0,\"heartbeat\":\"PT0S\"}}}", BlackholeConfig.class);

        assertEquals(Duration.ofSeconds(5), config.getClientRules().getSettle());
        assertEquals(3, config.getClientRules().getCatalogue().getMinPlayers(), "a minimum of 1 would defeat the k-anonymity");
        assertEquals(Duration.ofMinutes(15), config.getClientRules().getCatalogue().getHeartbeat());
    }

    @Test
    void theEnvironmentOverridesTheFileSwitch() {
        ClientRulesConfig fromFile = new Gson().fromJson("{\"clientRules\":{\"enabled\":true}}", BlackholeConfig.class).getClientRules();

        assertFalse(fromFile.withEnvironment(Map.of(ClientRulesConfig.ENV_ENABLED, "false")::get).isEnabled());
        assertTrue(fromFile.withEnvironment(Map.of(ClientRulesConfig.ENV_ENABLED, " ")::get).isEnabled());
        assertTrue(fromFile.withEnvironment(key -> null).isEnabled());
    }
}
