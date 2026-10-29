package net.onelitefeather.blackhole.backend.exemption;

import net.onelitefeather.blackhole.backend.imports.UUIDHasher;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The owner of a Minecraft UUID is the same value the proxy computes for it. The expected hash is a constant fixture, computed
 * from the SHA-512 of the UUID's canonical text, which is what Velocity's UUIDConverter hashes; no proxy code is loaded.
 */
class OwnerParityTest {

    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    private static final String EXPECTED_OWNER = "a8ef199e85ef6f7cae245a1700a8fcddf3ea26a619aa773731f0a6127726e49708ade4052fccd3239fa0517af4e1ebd7d60cfa195628010f5d86e640dc5e2166";

    @Test
    void theBackendOwnerOfAUuidEqualsTheProxyFixture() {
        assertEquals(EXPECTED_OWNER, ExemptionService.ownerOf(PLAYER), "the owner must match the proxy's hash of the same UUID");
    }

    @Test
    void theExemptionOwnerIsTheSameFunctionAsTheImportHash() {
        assertEquals(UUIDHasher.hash(PLAYER), ExemptionService.ownerOf(PLAYER), "one owner function for the whole backend");
    }
}
