package net.onelitefeather.blackhole.backend.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RolesTest {

    @Test
    void integrationRoleExpandsToItselfOnly() {
        assertEquals(List.of(Roles.INTEGRATION), Roles.expand(Roles.INTEGRATION),
                "the integration role implies nothing and is implied by nothing");
    }

    @Test
    void adminDoesNotImplyTheIntegrationRole() {
        assertFalse(Roles.expand(Roles.ADMIN).contains(Roles.INTEGRATION), "ADMIN must not gain integration submission");
    }

    @Test
    void moderatorDoesNotImplyTheIntegrationRole() {
        assertFalse(Roles.expand(Roles.MODERATOR).contains(Roles.INTEGRATION), "MODERATOR must not gain integration submission");
    }
}
