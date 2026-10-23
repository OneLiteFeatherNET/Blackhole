package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.elo.service.EloService;
import net.onelitefeather.blackhole.backend.security.Attribution;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CaseActorTest {

    private static final UUID PERSON = UUID.nameUUIDFromBytes("person".getBytes());
    private static final UUID PROXY = UUID.nameUUIDFromBytes("proxy".getBytes());
    private static final UUID INGAME = UUID.nameUUIDFromBytes("ingame".getBytes());

    @Test
    void aWebActionIsVerifiedAndTheRecorderIsThePerson() {
        CaseActor actor = CaseActor.of(new Attribution(PERSON, PERSON));

        assertEquals(ActorTrust.VERIFIED, actor.trust());
        assertEquals(PERSON, actor.actorOid(), "the person is the actor");
        assertEquals(PERSON, actor.recordedByOid());
        assertNull(actor.actorSource(), "no separate source UUID");
    }

    @Test
    void anInGameActionByALinkedStaffMemberIsVerifiedAndKeepsTheProxyAsRecorder() {
        CaseActor actor = CaseActor.of(new Attribution(INGAME, PROXY, PERSON));

        assertEquals(ActorTrust.VERIFIED, actor.trust());
        assertEquals(PERSON, actor.actorOid(), "the linked person");
        assertEquals(PROXY, actor.recordedByOid(), "the proxy wrote it");
        assertNull(actor.actorSource());
    }

    @Test
    void anInGameActionByAnUnlinkedUuidIsUnverifiedAndKeepsTheUuid() {
        CaseActor actor = CaseActor.of(new Attribution(INGAME, PROXY));

        assertEquals(ActorTrust.UNVERIFIED, actor.trust());
        assertNull(actor.actorOid(), "no person is known");
        assertEquals(INGAME, actor.actorSource(), "only the attributed uuid");
        assertEquals(PROXY, actor.recordedByOid());
    }

    @Test
    void aRowFromBeforeSsoIsUnverifiedWithItsSourceOnly() {
        CaseActor actor = CaseActor.ofRow(INGAME, null, null);

        assertEquals(ActorTrust.UNVERIFIED, actor.trust(), "a legacy actor must never look verified");
        assertEquals(INGAME, actor.actorSource());
    }

    @Test
    void aRowWithoutAnyActorIsUnverified() {
        assertEquals(CaseActor.unattributed(), CaseActor.ofRow(null, null, null));
    }

    @Test
    void theEloSystemIsTheSystem() {
        CaseActor actor = CaseActor.ofRow(EloService.SYSTEM_ELO_SOURCE, null, null);

        assertEquals(ActorTrust.SYSTEM, actor.trust());
        assertNull(actor.actorSource(), "the system has no source uuid to show");
    }
}
