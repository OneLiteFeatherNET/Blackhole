package net.onelitefeather.blackhole.backend.security;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorResolverTest {

    private static final UUID OID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private final TestClock clock = new TestClock();
    private final InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> store =
            InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
    private final ActorResolver resolver = new ActorResolver(this.store.repository(), this.clock);

    private static Authentication human(UUID oid, String name, String... roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("oid", oid.toString());
        claims.put("scp", "access_as_user");
        if (name != null) {
            claims.put("name", name);
        }
        return Authentication.build(oid.toString(), List.of(roles), claims);
    }

    private static Authentication service(UUID oid, String... roles) {
        return Authentication.build(oid.toString(), List.of(roles), Map.of("oid", oid.toString(), "name", "Blackhole Velocity"));
    }

    @Test
    void theFirstWriteOfAPrincipalCreatesItsStaffActorRow() {
        Actor actor = this.resolver.resolve(human(OID, "Alex Admin", Roles.MODERATOR));

        assertEquals(OID, actor.oid());
        StaffActorEntity row = this.store.all().getFirst();
        assertEquals(OID, row.getOid(), "row for the oid");
        assertEquals("Alex Admin", row.getDisplayName(), "name claim stored");
        assertEquals(ActorKind.HUMAN, row.getKind());
        assertEquals(this.clock.millis(), row.getFirstSeenAt(), "first seen now");
        assertEquals(this.clock.millis(), row.getLastSeenAt(), "last seen now");
    }

    @Test
    void aChangedNameClaimUpdatesTheDisplayNameAndKeepsTheOid() {
        this.resolver.resolve(human(OID, "Alex Admin", Roles.MODERATOR));
        this.clock.advance(Duration.ofMinutes(1));

        this.resolver.resolve(human(OID, "Alex Moderator", Roles.MODERATOR));

        assertEquals(1, this.store.all().size(), "still one row");
        StaffActorEntity row = this.store.all().getFirst();
        assertEquals(OID, row.getOid());
        assertEquals("Alex Moderator", row.getDisplayName());
        assertEquals(this.clock.millis(), row.getLastSeenAt(), "last seen moves with the update");
    }

    @Test
    void aDelegatedTokenIsAHumanAndAnAppOnlyTokenIsAService() {
        UUID serviceOid = UUID.fromString("22222222-2222-4222-8222-222222222222");

        Actor person = this.resolver.resolve(human(OID, "Alex", Roles.MODERATOR));
        Actor application = this.resolver.resolve(service(serviceOid, Roles.PROXY));

        assertEquals(ActorKind.HUMAN, person.kind());
        assertEquals(ActorKind.SERVICE, application.kind());
        assertEquals(ActorKind.SERVICE, this.store.repository().findById(serviceOid).orElseThrow().getKind(), "stored kind");
    }

    @Test
    void onlyAPrincipalWithTheProxyRoleCanVouchForSomeoneElse() {
        UUID serviceOid = UUID.fromString("22222222-2222-4222-8222-222222222222");

        assertFalse(this.resolver.resolve(human(OID, "Alex", Roles.MODERATOR)).proxy(), "a moderator is not the proxy");
        assertTrue(this.resolver.resolve(service(serviceOid, Roles.PROXY)).proxy(), "the proxy role is");
    }

    @Test
    void emailUpnAndPreferredUsernameAreNeverRead() {
        Set<String> read = new java.util.HashSet<>();
        Map<String, Object> claims = new HashMap<>() {
            @Override
            public Object get(Object key) {
                read.add(String.valueOf(key));
                return super.get(key);
            }

            @Override
            public boolean containsKey(Object key) {
                read.add(String.valueOf(key));
                return super.containsKey(key);
            }

            @Override
            public Set<Map.Entry<String, Object>> entrySet() {
                throw new AssertionError("the resolver must not iterate over all claims");
            }
        };
        claims.put("oid", OID.toString());
        claims.put("scp", "access_as_user");
        claims.put("name", "Alex");
        claims.put("email", "alex@example.org");
        claims.put("upn", "alex@example.org");
        claims.put("preferred_username", "alex@example.org");

        this.resolver.resolve(Authentication.build(OID.toString(), List.of(Roles.MODERATOR), claims));

        assertEquals(Set.of("oid", "name", "scp"), read, "only oid and name (plus the token-kind marker scp) are looked at");
        assertEquals("Alex", this.store.all().getFirst().getDisplayName(), "only the name claim is stored, no address");
    }

    @Test
    void aTokenWithoutAValidObjectIdIsUnauthorizedAndWritesNothing() {
        Authentication noOid = Authentication.build("x", List.of(Roles.MODERATOR), Map.of("scp", "access_as_user"));
        Authentication badOid = Authentication.build("x", List.of(Roles.MODERATOR), Map.of("oid", "not-a-uuid", "scp", "access_as_user"));

        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(HttpStatusException.class, () -> this.resolver.resolve(noOid)).getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(HttpStatusException.class, () -> this.resolver.resolve(badOid)).getStatus());
        assertTrue(this.store.all().isEmpty(), "no row for an unidentified principal");
    }

    @Test
    void anUnchangedPrincipalIsNotRewrittenOnEveryRequest() {
        AtomicInteger updates = new AtomicInteger();
        InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> counting =
                InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid)
                        .finder("update", args -> {
                            updates.incrementAndGet();
                            return args[0];
                        })
                        .build();
        ActorResolver countingResolver = new ActorResolver(counting.repository(), this.clock);
        StaffActorEntity seen = new StaffActorEntity(OID, ActorKind.HUMAN, "Alex", this.clock.millis());
        seen.setRoles("MODERATOR");
        seen.setRolesSeenAt(this.clock.millis());
        counting.given(seen, OID);

        this.clock.advance(Duration.ofMinutes(5));
        countingResolver.resolve(human(OID, "Alex", Roles.MODERATOR));

        assertEquals(0, updates.get(), "same name, same kind, seen minutes ago: no write");
    }

    @Test
    void aStaleLastSeenIsRefreshed() {
        this.resolver.resolve(human(OID, "Alex", Roles.MODERATOR));
        this.clock.advance(ActorResolver.REFRESH_AFTER.plusMinutes(1));

        this.resolver.resolve(human(OID, "Alex", Roles.MODERATOR));

        assertEquals(this.clock.millis(), this.store.all().getFirst().getLastSeenAt());
    }

    @Test
    void aFirstWriteThatLosesTheInsertRaceUsesTheRowTheWinnerCreated() {
        StaffActorEntity winner = new StaffActorEntity(OID, ActorKind.HUMAN, "Alex", this.clock.millis());
        AtomicInteger lookups = new AtomicInteger();
        InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> racing =
                InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid)
                        .finder("findById", args -> lookups.getAndIncrement() == 0 ? Optional.empty() : Optional.of(winner))
                        .finder("save", args -> {
                            throw new IllegalStateException("duplicate key");
                        })
                        .build();

        Actor actor = new ActorResolver(racing.repository(), this.clock).resolve(human(OID, "Alex", Roles.MODERATOR));

        assertEquals(OID, actor.oid(), "the request still succeeds");
    }
}
