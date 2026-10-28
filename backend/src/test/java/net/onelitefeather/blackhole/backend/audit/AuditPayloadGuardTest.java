package net.onelitefeather.blackhole.backend.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditPayloadGuardTest {

    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTRY = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final AuditPayloadGuard guard = new AuditPayloadGuard();

    @Test
    void acceptsPayloadWithOnlyFixedIdentifiers() {
        Map<String, Object> payload = Map.of("auditId", ENTRY.toString(), "actorOid", ACTOR.toString(), "targetRef", "AB12CD34");

        assertDoesNotThrow(() -> guard.check(payload, ACTOR, ENTRY));
    }

    @Test
    void refusesIpv4Address() {
        Map<String, Object> payload = Map.of("targetRef", "connected from 192.168.0.12");

        assertThrows(IllegalArgumentException.class, () -> guard.check(payload, ACTOR, ENTRY));
    }

    @Test
    void refusesRawPlayerUuid() {
        Map<String, Object> payload = Map.of("targetRef", "069a79f4-44e9-4726-a5be-fca90e38aaf5");

        assertThrows(IllegalArgumentException.class, () -> guard.check(payload, ACTOR, ENTRY));
    }
}
