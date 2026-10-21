package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.model.EloProfileResponse;
import net.onelitefeather.blackhole.client.model.EloSectionScore;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static net.onelitefeather.blackhole.velocity.TestData.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EloReportTest {

    private static EloSectionScore score(String section, int score, Integer soft, int permanent) {
        return new EloSectionScore().section(section).displayName(section.toUpperCase()).score(score)
                .softThreshold(soft).permanentThreshold(permanent);
    }

    private static EloProfileResponse elo(EloSectionScore... scores) {
        return new EloProfileResponse().owner(OWNER).sections(List.of(scores));
    }

    @Test
    void everySectionGetsARowWithItsScoreAndThresholds() {
        var rows = EloReport.rows(elo(score("network", 950, 700, 300), score("chat", 400, null, 300)), Optional.empty());

        assertEquals(List.of("chat", "network"), rows.stream().map(EloReport.Row::section).toList(), "ordered by key");
        assertEquals(400, rows.getFirst().score());
        assertNull(rows.getFirst().softThreshold(), "a section without soft threshold shows none");
        assertEquals(700, rows.get(1).softThreshold());
        assertEquals(300, rows.get(1).permanentThreshold());
    }

    @Test
    void aPermanentPunishmentIsFlaggedOnlyInItsSection() {
        var profile = new PunishProfileDTO().owner(OWNER).active(Map.of(
                "network", new PunishEntryDTO().permanent(true),
                "chat", new PunishEntryDTO().permanent(false)));

        var rows = EloReport.rows(elo(score("network", 100, 700, 300), score("chat", 600, 700, 300)), Optional.of(profile));

        assertFalse(rows.get(0).permanentActive(), "the chat punishment is temporary");
        assertTrue(rows.get(1).permanentActive(), "the network ban is permanent");
    }

    @Test
    void aPlayerWithoutProfileHasNoPermanentPunishment() {
        assertFalse(EloReport.rows(elo(score("network", 1000, 700, 300)), Optional.empty()).getFirst().permanentActive());
    }
}
