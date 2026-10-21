package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.model.EloProfileResponse;
import net.onelitefeather.blackhole.client.model.EloSectionScore;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What {@code /elo} shows: one row per section with the player's score, the section's thresholds
 * and whether a permanent punishment is active there.
 */
public final class EloReport {

    /**
     * @param section            the section key
     * @param displayName        the section's display name
     * @param score              the player's score
     * @param softThreshold      the soft threshold, null if the section has none
     * @param permanentThreshold the score below which the section punishes permanently
     * @param permanentActive    whether a permanent punishment is active in the section
     */
    public record Row(String section, String displayName, int score, Integer softThreshold, int permanentThreshold, boolean permanentActive) {
    }

    private EloReport() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param elo     the player's scores per section
     * @param profile the player's punishment profile, if any
     * @return the rows, ordered by section key
     */
    public static List<Row> rows(EloProfileResponse elo, Optional<PunishProfileDTO> profile) {
        return elo.getSections().stream()
                .sorted(Comparator.comparing(EloSectionScore::getSection))
                .map(score -> new Row(
                        score.getSection(), score.getDisplayName(), score.getScore(), score.getSoftThreshold(),
                        score.getPermanentThreshold(), permanentActive(profile, score.getSection())))
                .toList();
    }

    private static boolean permanentActive(Optional<PunishProfileDTO> profile, String section) {
        return profile.map(PunishProfileDTO::getActive)
                .map(active -> active.get(section))
                .map(PunishEntryDTO::getPermanent)
                .orElse(false);
    }
}
