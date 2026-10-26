package net.onelitefeather.blackhole.backend.kick;

import net.onelitefeather.blackhole.backend.kick.dto.KickDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules of a kick request that need no database. */
class KickRequestValidatorTest {

    private static final String OWNER = "ab".repeat(64);

    private static KickDTO.CreateRequest kick(String reason) {
        return new KickDTO.CreateRequest(OWNER, KickScope.NETWORK, null, reason);
    }

    private static KickDTO.CreateRequest kick(KickScope scope, String target) {
        return new KickDTO.CreateRequest(OWNER, scope, target, "AFK farming");
    }

    private static void assertRefused(Optional<KickDTO.Error> error, ErrorKind kind, String what) {
        assertTrue(error.isPresent(), what + " must be refused");
        assertEquals(kind, error.get().kind(), what);
    }

    private static void assertAccepted(Optional<KickDTO.Error> error, String what) {
        assertTrue(error.isEmpty(), what + " must be accepted but was " + error);
    }

    @Test
    void aPlainReasonIsAccepted() {
        assertAccepted(KickValidator.validate(kick("AFK farming")), "a plain reason");
    }

    @Test
    void aReasonOfOneCharacterIsAccepted() {
        assertAccepted(KickValidator.validate(kick("x")), "the shortest reason");
    }

    @Test
    void aReasonOfTwoHundredCharactersIsAccepted() {
        assertAccepted(KickValidator.validate(kick("x".repeat(200))), "the longest reason");
    }

    @Test
    void aReasonOfTwoHundredAndOneCharactersIsRefused() {
        assertRefused(KickValidator.validate(kick("x".repeat(201))), ErrorKind.VALIDATION, "a reason one over the limit");
    }

    @Test
    void anEmptyOrBlankReasonIsRefused() {
        assertRefused(KickValidator.validate(kick("")), ErrorKind.VALIDATION, "an empty reason");
        assertRefused(KickValidator.validate(kick("   ")), ErrorKind.VALIDATION, "a blank reason");
        assertRefused(KickValidator.validate(kick(null)), ErrorKind.VALIDATION, "a missing reason");
    }

    @Test
    void aReasonWithAUuidIsRefusedWithoutEchoingIt() {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";

        Optional<KickDTO.Error> error = KickValidator.validate(kick("kicked " + uuid));

        assertRefused(error, ErrorKind.VALIDATION, "a reason with a player uuid");
        assertTrue(!error.get().errorMessage().contains(uuid), "the answer must not repeat the uuid");
    }

    @Test
    void aReasonWithAnIpAddressIsRefused() {
        assertRefused(KickValidator.validate(kick("from 192.168.1.20")), ErrorKind.VALIDATION, "a reason with an ip");
    }

    @Test
    void aReasonWithABanIdIsRefused() {
        assertRefused(KickValidator.validate(kick("see #K7M29QXA")), ErrorKind.VALIDATION, "a reason with a Ban ID");
    }

    @Test
    void anOwnerThatIsNoHashIsRefused() {
        assertRefused(KickValidator.validate(new KickDTO.CreateRequest("not-a-hash", KickScope.NETWORK, null, "x")),
                ErrorKind.VALIDATION, "an owner that is no sha-512 hash");
    }

    @Test
    void aServerScopeWithoutTargetIsATargetError() {
        assertRefused(KickValidator.validate(kick(KickScope.SERVER, null)), ErrorKind.KICK_TARGET_INVALID, "a server scope without target");
        assertRefused(KickValidator.validate(kick(KickScope.SERVER, "  ")), ErrorKind.KICK_TARGET_INVALID, "a server scope with a blank target");
    }

    @Test
    void aSectionScopeWithoutTargetIsATargetError() {
        assertRefused(KickValidator.validate(kick(KickScope.SECTION, null)), ErrorKind.KICK_TARGET_INVALID, "a section scope without target");
    }

    @Test
    void aNetworkScopeWithATargetIsATargetError() {
        assertRefused(KickValidator.validate(kick(KickScope.NETWORK, "lobby-1")), ErrorKind.KICK_TARGET_INVALID, "a network kick has no target");
    }

    @Test
    void aServerTargetWithOddCharactersIsATargetError() {
        assertRefused(KickValidator.validate(kick(KickScope.SERVER, "lobby 1; drop")), ErrorKind.KICK_TARGET_INVALID, "a server name with spaces");
    }

    @Test
    void aScopedKickWithATargetIsAccepted() {
        assertAccepted(KickValidator.validate(kick(KickScope.SERVER, "Lobby-1")), "a server kick with a server name");
        assertAccepted(KickValidator.validate(kick(KickScope.SECTION, "chat")), "a section kick with a section key");
    }

    @Test
    void noScopeMeansTheWholeNetwork() {
        assertEquals(KickScope.NETWORK, KickValidator.scopeOf(new KickDTO.CreateRequest(OWNER, null, null, "x")), "default scope");
        assertAccepted(KickValidator.validate(new KickDTO.CreateRequest(OWNER, null, null, "x")), "a request without scope");
    }

    @Test
    void aMissingIdempotencyKeyIsRefused() {
        assertRefused(KickValidator.validateKey(null), ErrorKind.VALIDATION, "no Idempotency-Key");
        assertRefused(KickValidator.validateKey(""), ErrorKind.VALIDATION, "an empty Idempotency-Key");
    }

    @Test
    void anIdempotencyKeyOfEightyAllowedCharactersIsAccepted() {
        assertAccepted(KickValidator.validateKey("aZ09._:-".repeat(10)), "80 characters of the alphabet");
    }

    @Test
    void anIdempotencyKeyOfEightyOneCharactersIsRefused() {
        assertRefused(KickValidator.validateKey("a".repeat(81)), ErrorKind.VALIDATION, "81 characters");
    }

    @Test
    void anIdempotencyKeyWithOtherCharactersIsRefused() {
        assertRefused(KickValidator.validateKey("two words"), ErrorKind.VALIDATION, "a space");
        assertRefused(KickValidator.validateKey("a/b"), ErrorKind.VALIDATION, "a slash");
    }

    @Test
    void aMassKickNeedsAServerOrSectionScopeAndATarget() {
        assertAccepted(KickValidator.validate(new KickDTO.MassCreateRequest(KickScope.SERVER, "Lobby-1", "maintenance")), "a server mass kick");
        assertAccepted(KickValidator.validate(new KickDTO.MassCreateRequest(KickScope.SECTION, "chat", "maintenance")), "a section mass kick");
        assertRefused(KickValidator.validate(new KickDTO.MassCreateRequest(KickScope.NETWORK, null, "maintenance")),
                ErrorKind.KICK_TARGET_INVALID, "a network mass kick");
        assertRefused(KickValidator.validate(new KickDTO.MassCreateRequest(null, "chat", "maintenance")),
                ErrorKind.KICK_TARGET_INVALID, "a mass kick without scope");
        assertRefused(KickValidator.validate(new KickDTO.MassCreateRequest(KickScope.SERVER, null, "maintenance")),
                ErrorKind.KICK_TARGET_INVALID, "a mass kick without target");
    }

    @Test
    void aMassKickReasonFollowsTheSameRules() {
        assertRefused(KickValidator.validate(new KickDTO.MassCreateRequest(KickScope.SERVER, "Lobby-1", "ip 10.0.0.1")),
                ErrorKind.VALIDATION, "an ip in a mass kick reason");
    }
}
