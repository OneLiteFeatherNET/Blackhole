package net.onelitefeather.blackhole.velocity.section;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionRegistryTest {

    private static final SectionInfo CHAT = new SectionInfo("chat", "Chat", EnforcementKind.MUTE, List.of());
    private static final SectionInfo NETWORK = new SectionInfo("network", "Network", EnforcementKind.BAN, List.of());
    private static final SectionInfo BEDWARS = new SectionInfo("bedwars", "Bedwars", EnforcementKind.SERVER, List.of("bedwars-*"));

    /** A source whose answers each test sets up; counts API calls. */
    private static final class StubSource implements SectionRegistry.Source {
        List<SectionInfo> api = List.of();
        boolean apiDown;
        int apiCalls;

        @Override
        public List<SectionInfo> load() throws Exception {
            this.apiCalls++;
            if (this.apiDown) {
                throw new IOException("backend down");
            }
            return this.api;
        }
    }

    private final StubSource source = new StubSource();
    private final SectionRegistry registry = new SectionRegistry(source);

    @Test
    void theSectionsAreReadFromTheBackendOnEveryReload() {
        source.api = List.of(NETWORK);

        assertTrue(registry.reload());
        registry.reload();

        assertTrue(registry.find("network").isPresent());
        assertEquals(2, source.apiCalls, "no cache in between: a reload is a backend read");
    }

    @Test
    void aFailedReloadKeepsThePreviousSections() {
        source.api = List.of(CHAT);
        registry.reload();
        source.apiDown = true;

        boolean reloaded = registry.reload();

        assertFalse(reloaded, "nothing new was installed");
        assertTrue(registry.find("chat").isPresent(), "enforcement must keep working on the old list");
    }

    @Test
    void aSuccessfulReloadReplacesTheSnapshot() {
        source.api = List.of(CHAT);
        registry.reload();
        source.api = List.of(NETWORK);

        registry.reload();

        assertFalse(registry.find("chat").isPresent(), "a removed section is gone");
        assertTrue(registry.find("network").isPresent());
    }

    @Test
    void sectionsCanBeSelectedByEnforcementKind() {
        source.api = List.of(CHAT, NETWORK, BEDWARS);
        registry.reload();

        assertEquals(List.of("network", "bedwars"),
                registry.ofKind(EnforcementKind.BAN, EnforcementKind.SERVER).stream().map(SectionInfo::key).sorted((a, b) -> b.compareTo(a)).toList(),
                "ban and server sections");
        assertEquals(1, registry.ofKind(EnforcementKind.MUTE).size());
    }

    @Test
    void anUnknownSectionIsNotFound() {
        assertTrue(registry.find("ghost").isEmpty());
    }
}
