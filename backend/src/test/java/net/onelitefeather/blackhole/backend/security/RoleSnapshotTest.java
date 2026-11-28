package net.onelitefeather.blackhole.backend.security;

import io.micronaut.security.authentication.Authentication;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoleSnapshotTest {

    private static final UUID OID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private final TestClock clock = new TestClock();
    private final InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> store =
            InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
    private final ActorResolver resolver = new ActorResolver(this.store.repository(), this.clock);

    private static Authentication human(UUID oid, String... roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("oid", oid.toString());
        claims.put("scp", "access_as_user");
        claims.put("name", "Alex");
        return Authentication.build(oid.toString(), List.of(roles), claims);
    }

    @Test
    void aVerifiedHumanModeratorRequestStoresTheMODERATORRoleAndTheRequestTime() {
        this.resolver.resolve(human(OID, Roles.MODERATOR, Roles.VIEWER));

        StaffActorEntity row = this.store.repository().findById(OID).orElseThrow();
        assertEquals("MODERATOR,VIEWER", row.getRoles(), "the staff role names of the token");
        assertEquals(this.clock.millis(), row.getRolesSeenAt(), "the snapshot time is the time of the request");
    }

    @Test
    void theSnapshotStoresOnlyTheStaffRoleNamesAndNoOtherRole() {
        this.resolver.resolve(human(OID, Roles.MODERATOR, Roles.PROXY, Roles.METRICS));

        assertEquals("MODERATOR", this.store.repository().findById(OID).orElseThrow().getRoles(), "PROXY and METRICS are not stored");
    }

    @Test
    void anAppOnlyRequestStoresNoRoleSnapshot() {
        UUID serviceOid = UUID.fromString("22222222-2222-4222-8222-222222222222");
        this.resolver.resolve(Authentication.build(serviceOid.toString(), List.of(Roles.PROXY),
                Map.of("oid", serviceOid.toString(), "name", "Blackhole Velocity")));

        StaffActorEntity row = this.store.repository().findById(serviceOid).orElseThrow();
        assertNull(row.getRoles(), "a SERVICE caller has no role snapshot");
        assertNull(row.getRolesSeenAt(), "a SERVICE caller has no snapshot time");
    }
}
