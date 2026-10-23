package net.onelitefeather.blackhole.velocity.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
