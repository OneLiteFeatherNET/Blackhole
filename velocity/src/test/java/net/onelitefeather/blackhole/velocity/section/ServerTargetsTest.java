package net.onelitefeather.blackhole.velocity.section;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.ServerTargetKind;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerFacts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTargetsTest {

    private static final Optional<ServerFacts> BEDWARS_3 =
            Optional.of(new ServerFacts("BedWars-3", "BedWars", Set.of("Minigames", "Global")));

    private static SectionInfo section(ServerTargetKind kind, String... names) {
        return new SectionInfo("s", "S", EnforcementKind.SERVER, List.of(names), kind);
    }

    @Test
    void aTaskSectionCoversAServiceOfTheTask() {
        assertTrue(ServerTargets.covers(section(ServerTargetKind.CLOUDNET_TASK, "BedWars"), "BedWars-3", BEDWARS_3));
    }

    @Test
    void aTaskSectionDoesNotCoverAnotherTask() {
        assertFalse(ServerTargets.covers(section(ServerTargetKind.CLOUDNET_TASK, "SkyWars"), "BedWars-3", BEDWARS_3));
    }

    @Test
    void aGroupSectionCoversAServiceInTheGroup() {
        assertTrue(ServerTargets.covers(section(ServerTargetKind.CLOUDNET_GROUP, "Minigames"), "BedWars-3", BEDWARS_3));
    }

    @Test
    void aGroupSectionDoesNotCoverAServiceOutsideTheGroup() {
        assertFalse(ServerTargets.covers(section(ServerTargetKind.CLOUDNET_GROUP, "Lobbies"), "BedWars-3", BEDWARS_3));
    }

    @Test
    void aServiceSectionCoversOnlyThatService() {
        SectionInfo section = section(ServerTargetKind.CLOUDNET_SERVICE, "BedWars-3");

        assertTrue(ServerTargets.covers(section, "BedWars-3", BEDWARS_3));
        assertFalse(ServerTargets.covers(section, "BedWars-4",
                Optional.of(new ServerFacts("BedWars-4", "BedWars", Set.of("Minigames")))));
    }

    @Test
    void cloudNetNamesMatchIgnoringCase() {
        assertTrue(ServerTargets.covers(section(ServerTargetKind.CLOUDNET_TASK, "bedwars"), "BedWars-3", BEDWARS_3));
        assertTrue(ServerTargets.covers(section(ServerTargetKind.CLOUDNET_GROUP, "MINIGAMES"), "BedWars-3", BEDWARS_3));
        assertTrue(ServerTargets.covers(section(ServerTargetKind.CLOUDNET_SERVICE, "bedwars-3"), "BedWars-3", BEDWARS_3));
    }

    @Test
    void aCloudNetNameIsNotAGlob() {
        assertFalse(ServerTargets.covers(section(ServerTargetKind.CLOUDNET_TASK, "Bed*"), "BedWars-3", BEDWARS_3));
    }

    @Test
    void aCloudNetSectionCoversNothingWithoutFacts() {
        for (ServerTargetKind kind : new ServerTargetKind[]{
                ServerTargetKind.CLOUDNET_TASK, ServerTargetKind.CLOUDNET_GROUP, ServerTargetKind.CLOUDNET_SERVICE}) {
            assertFalse(ServerTargets.covers(section(kind, "BedWars", "BedWars-3", "Minigames"), "BedWars-3", Optional.empty()),
                    kind + " must not cover a server CloudNet does not know");
        }
    }

    @Test
    void aPatternSectionStillMatchesTheServerNameWithoutCloudNet() {
        SectionInfo section = section(ServerTargetKind.PATTERN, "lobby-*");

        assertTrue(ServerTargets.covers(section, "lobby-1", Optional.empty()));
        assertFalse(ServerTargets.covers(section, "bedwars-1", Optional.empty()));
    }

    @Test
    void aSectionWithoutTargetKindIsAPatternSection() {
        SectionInfo section = new SectionInfo("s", "S", EnforcementKind.SERVER, List.of("lobby-*"), null);

        assertEquals(ServerTargetKind.PATTERN, section.targetKind());
        assertTrue(ServerTargets.covers(section, "lobby-1", Optional.empty()));
    }
}
