package net.onelitefeather.blackhole.api.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class IdGeneratorTest {


    @Test
    @DisplayName("Test generateId with random UUID")
    void testGenerateId() {
        var id = IdGenerator.generateId();

        Assertions.assertEquals(22, id.length());
    }

    @Test
    @DisplayName("Test generateId with given UUID")
    void testGenerateIdByGivenUUID() {
        var id = IdGenerator.generateId(new UUID(0,0));

        Assertions.assertEquals(22, id.length());
        Assertions.assertEquals("AAAAAAAAAAAAAAAAAAAAAA", id);
    }
}
