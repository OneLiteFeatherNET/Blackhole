package net.onelitefeather.blackhole.backend.evasion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientFingerprintTraitsTest {

    private static ClientFingerprintTraits traits(String brand, List<String> channels, List<String> mods) {
        return ClientFingerprintTraits.of(brand, channels, mods).orElseThrow();
    }

    @Test
    void theCanonicalFormIsLengthPrefixedAndVersioned() {
        ClientFingerprintTraits traits = traits("fabric", List.of("meteor-client:main", "fabric:registry/sync"), List.of());

        assertEquals("v1\nbrand:6:fabric\nchannels:2:20:fabric:registry/sync18:meteor-client:main\nmods:0:", traits.canonical());
    }

    @Test
    void emptyTraitsHaveTheEmptyCanonicalForm() {
        assertEquals("v1\nbrand:0:\nchannels:0:\nmods:0:", traits(null, null, null).canonical());
    }

    @Test
    void theOrderOfChannelsAndModsDoesNotMatterAndDuplicatesCollapse() {
        assertEquals(traits("x", List.of("a:b", "c:d"), List.of("m1@1", "m2@2")).canonical(),
                traits("x", List.of("c:d", "a:b", "a:b"), List.of("m2@2", "m1@1")).canonical());
    }

    @Test
    void lengthPrefixesKeepValuesFromRunningIntoEachOther() {
        assertNotEquals(traits("x", List.of("ab", "c"), List.of()).canonical(), traits("x", List.of("a", "bc"), List.of()).canonical());
        assertNotEquals(traits("x", List.of("a"), List.of("b")).canonical(), traits("x", List.of("a", "b"), List.of()).canonical());
    }

    @Test
    void theBrandIsTrimmedLowerCasedAndStrippedOfControlCharacters() {
        assertEquals("lunarclient:abc", traits("  LunarClient:\u0007abc \n", List.of(), List.of()).brand());
    }

    @Test
    void theLengthCountsUtf8BytesSoMultibyteValuesStayUnambiguous() {
        assertTrue(traits("ä", List.of(), List.of()).canonical().contains("brand:2:ä"));
    }

    @Test
    void theMinecraftNamespaceIsDroppedFromTheChannels() {
        assertEquals(List.of("fabric:registry/sync"), traits("fabric", List.of("minecraft:brand", "minecraft:register", "fabric:registry/sync"), List.of()).channels());
    }

    @Test
    void emptyTooLongAndControlCharacterEntriesAreDropped() {
        List<String> channels = List.of("", "  ", "a".repeat(129), "bad\u0000name", "ok:one");

        assertEquals(List.of("ok:one"), traits("x", channels, List.of()).channels());
    }

    @Test
    void anOversizedBrandOrListIsRefused() {
        assertTrue(ClientFingerprintTraits.of("a".repeat(65), List.of(), List.of()).isEmpty(), "brand of 65");
        assertTrue(ClientFingerprintTraits.of("a".repeat(64), List.of(), List.of()).isPresent(), "brand of 64");
        List<String> tooMany = IntStream.range(0, 257).mapToObj(i -> "c:" + i).toList();
        assertTrue(ClientFingerprintTraits.of("x", tooMany, List.of()).isEmpty(), "257 channels");
        assertTrue(ClientFingerprintTraits.of("x", List.of(), tooMany).isEmpty(), "257 mods");
        assertTrue(ClientFingerprintTraits.of("x", tooMany.subList(0, 256), List.of()).isPresent(), "256 channels");
    }

    @Test
    void plainVanillaFabricForgeAndNeoforgeClientsAreGeneric() {
        for (String brand : new String[]{null, "", "vanilla", "Vanilla", "fabric", "forge", "neoforge"}) {
            assertTrue(traits(brand, List.of("minecraft:brand"), List.of()).isGeneric(), "brand " + brand);
        }
    }

    @Test
    void anyOtherBrandOrAnyChannelOrModMakesTheTraitsSpecific() {
        assertFalse(traits("lunarclient:abc", List.of(), List.of()).isGeneric());
        assertFalse(traits("fabric", List.of("meteor-client:main"), List.of()).isGeneric());
        assertFalse(traits("forge", List.of(), List.of("jei@4")).isGeneric());
    }

    @Test
    void theProtocolVersionIsNotPartOfTheTraits() {
        // there is no parameter for it: the canonical form depends on brand, channels and mods only
        assertFalse(traits("x", List.of(), List.of()).canonical().toLowerCase().contains("protocol"));
    }

    @Test
    void toStringNeverPrintsATrait() {
        assertFalse(traits("secretbrand", new ArrayList<>(List.of("secret:channel")), List.of()).toString().contains("secret"));
    }
}
