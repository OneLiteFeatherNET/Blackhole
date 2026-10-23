package net.onelitefeather.blackhole.velocity.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudNetConfigTest {

    @Test
    void theDefaultIsAuto() {
        assertEquals("auto", new CloudNetConfig().getIntegration());
        assertEquals("auto", new BlackholeConfig().getCloudnet().getIntegration());
    }

    @Test
    void aConfigWithoutTheSectionIsAuto() {
        assertEquals("auto", new com.google.gson.Gson().fromJson("{}", BlackholeConfig.class).getCloudnet().getIntegration());
    }

    @Test
    void offIsReadFromTheFile() {
        BlackholeConfig config = new com.google.gson.Gson().fromJson("{\"cloudnet\":{\"integration\":\"OFF\"}}", BlackholeConfig.class);

        assertEquals("off", config.getCloudnet().getIntegration());
    }

    @Test
    void anUnknownValueCountsAsAuto() {
        BlackholeConfig config = new com.google.gson.Gson().fromJson("{\"cloudnet\":{\"integration\":\"maybe\"}}", BlackholeConfig.class);

        assertEquals("auto", config.getCloudnet().getIntegration());
    }

    @Test
    void theEnvironmentOverridesTheFile() {
        CloudNetConfig fromFile = new CloudNetConfig();

        assertEquals("off", fromFile.withEnvironment(Map.of(CloudNetConfig.ENV_INTEGRATION, "off")::get).getIntegration());
    }

    @Test
    void aBlankEnvironmentValueKeepsTheFile() {
        assertEquals("auto", new CloudNetConfig().withEnvironment(Map.of(CloudNetConfig.ENV_INTEGRATION, " ")::get).getIntegration());
    }

    @Test
    void reportingDefaultsAreFiveSecondsAndFiveMinutes() {
        CloudNetConfig config = new CloudNetConfig();

        assertEquals(Duration.ofSeconds(5), config.getReportDebounce());
        assertEquals(Duration.ofMinutes(5), config.getHeartbeat());
        assertEquals(Optional.empty(), config.getProxyId());
    }

    @Test
    void reportingSettingsAreReadFromTheFile() {
        BlackholeConfig config = new com.google.gson.Gson().fromJson(
                "{\"cloudnet\":{\"proxyId\":\" Proxy-9 \",\"reportDebounce\":\"PT2S\",\"heartbeat\":\"PT1M\"}}", BlackholeConfig.class);

        assertEquals(Optional.of("Proxy-9"), config.getCloudnet().getProxyId());
        assertEquals(Duration.ofSeconds(2), config.getCloudnet().getReportDebounce());
        assertEquals(Duration.ofMinutes(1), config.getCloudnet().getHeartbeat());
    }

    @Test
    void anUnreadableOrTooShortDurationFallsBackToTheDefault() {
        BlackholeConfig config = new com.google.gson.Gson().fromJson(
                "{\"cloudnet\":{\"reportDebounce\":\"soon\",\"heartbeat\":\"PT0S\"}}", BlackholeConfig.class);

        assertEquals(Duration.ofSeconds(5), config.getCloudnet().getReportDebounce());
        assertEquals(Duration.ofMinutes(5), config.getCloudnet().getHeartbeat());
    }

    @Test
    void theEnvironmentKeepsTheReportingSettings() {
        BlackholeConfig config = new com.google.gson.Gson().fromJson("{\"cloudnet\":{\"proxyId\":\"Proxy-9\"}}", BlackholeConfig.class);

        assertEquals(Optional.of("Proxy-9"), config.getCloudnet().withEnvironment(name -> null).getProxyId());
    }
}
