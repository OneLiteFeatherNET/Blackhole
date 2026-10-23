package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetResolverTest {

    private int backendCalls;
    private Optional<TargetResolver.Known> backendAnswer = Optional.empty();
    private boolean backendFails;

    private final TargetResolver resolver = new TargetResolver(
            name -> Optional.empty(),
            name -> {
                this.backendCalls++;
                if (this.backendFails) {
                    throw new ApiException(404, "unknown");
                }
                return this.backendAnswer;
            });

    @Test
    void aUuidTypedAsTheTargetNeedsNoBackendCall() {
        UUID uuid = UUID.randomUUID();

        TargetResolver.ResolvedTarget target = resolver.resolve(uuid.toString());

        assertEquals(uuid, target.uuid());
        assertNull(target.onlinePlayer(), "a UUID literal is treated as an offline target");
        assertEquals(0, backendCalls);
    }

    @Test
    void anOfflineNameIsResolvedByTheBackend() {
        UUID uuid = UUID.randomUUID();
        backendAnswer = Optional.of(new TargetResolver.Known(uuid, "Steve"));

        TargetResolver.ResolvedTarget target = resolver.resolve("steve");

        assertEquals(uuid, target.uuid());
        assertEquals("Steve", target.name(), "the canonical name from the resolver is used");
        assertNull(target.onlinePlayer());
    }

    @Test
    void aNameNobodyKnowsFailsWithAnArgumentErrorNamingTheInput() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve("nobody"));

        assertTrue(error.getMessage().contains("nobody"), "the message names the input: " + error.getMessage());
    }

    @Test
    void anUnknownNameFailsWithTheTargetNotFoundErrorCarryingTheInput() {
        TargetNotFoundException error = assertThrows(TargetNotFoundException.class, () -> resolver.resolve("nobody"));

        assertEquals("nobody", error.input(), "the error message repeats what was typed");
    }

    @Test
    void aBackendFailureWhileResolvingIsAnArgumentErrorToo() {
        backendFails = true;

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("steve"));
    }

    @Test
    void theOwnerHashIsTheSha512OfTheUuid() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");

        String hash = new TargetResolver.ResolvedTarget(uuid, "x", null).ownerHash();

        assertEquals(128, hash.length(), "sha-512 as hex");
        assertFalse(hash.isBlank());
    }
}
