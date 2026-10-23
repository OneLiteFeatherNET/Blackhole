package net.onelitefeather.blackhole.velocity.cloudnet;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.ServerTargetKind;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerResolverTest {

    private static final SectionInfo TASK = new SectionInfo("bedwars", "BedWars", EnforcementKind.SERVER,
            List.of("BedWars"), ServerTargetKind.CLOUDNET_TASK);

    private final List<String> warnings = new ArrayList<>();

    private ServerResolver resolver(CloudNetView view, SectionRegistry sections) {
        return new ServerResolver(view, sections, this.warnings::add);
    }

    @Test
    void aKnownServerIsResolvedWithoutAWarning() {
        var view = new FakeCloudNetView(true).service("BedWars-3", "BedWars", "Minigames");

        var facts = resolver(view, TestData.registry(TASK)).facts("bedwars-3");

        assertEquals("BedWars", facts.orElseThrow().taskName());
        assertTrue(this.warnings.isEmpty(), "nothing to warn about: " + this.warnings);
    }

    @Test
    void anUnknownServerPassesAndWarnsOnce() {
        var resolver = resolver(new FakeCloudNetView(true), TestData.registry(TASK));

        assertTrue(resolver.facts("static-1").isEmpty());
        assertTrue(resolver.facts("STATIC-1").isEmpty());

        assertEquals(1, this.warnings.size(), "one warning per server name: " + this.warnings);
        assertTrue(this.warnings.get(0).contains("static-1"), this.warnings.get(0));
    }

    @Test
    void eachUnknownServerWarnsOnce() {
        var resolver = resolver(new FakeCloudNetView(true), TestData.registry(TASK));

        resolver.facts("a-1");
        resolver.facts("b-1");

        assertEquals(2, this.warnings.size());
    }

    @Test
    void theWarnedNamesAreBounded() {
        var resolver = resolver(new FakeCloudNetView(true), TestData.registry(TASK));

        for (int i = 0; i < ServerResolver.MAX_WARNED + 1; i++) {
            resolver.facts("server-" + i);
        }
        resolver.facts("server-0");

        assertEquals(ServerResolver.MAX_WARNED + 2, this.warnings.size(),
                "the oldest name was forgotten, so it warns again instead of the memory growing");
    }

    @Test
    void noWarningWithoutCloudNetSections() {
        resolver(new FakeCloudNetView(true), TestData.standardRegistry()).facts("lobby-1");

        assertTrue(this.warnings.isEmpty(), "pattern sections need no CloudNet: " + this.warnings);
    }

    @Test
    void noPerServerWarningWhenCloudNetIsNotAvailable() {
        resolver(new NoCloudNetView(), TestData.registry(TASK)).facts("lobby-1");

        assertTrue(this.warnings.isEmpty(), "the startup warning covers this case: " + this.warnings);
    }
}
