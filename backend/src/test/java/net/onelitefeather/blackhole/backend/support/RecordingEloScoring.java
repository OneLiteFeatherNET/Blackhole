package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.elo.EloChange;
import net.onelitefeather.blackhole.backend.elo.EloReasonCode;
import net.onelitefeather.blackhole.backend.elo.EloScoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An {@link EloScoring} that records every request and keeps simple per-section scores (base 1000),
 * for tests of the services that feed the ELO engine.
 */
public final class RecordingEloScoring implements EloScoring {

    /** One recorded delta. */
    public record Delta(String owner, String section, int delta, EloReasonCode reason, Map<String, Object> metaData) {
    }

    /** One recorded pardon reset. */
    public record Reset(String owner, String section, int pardonScore) {
    }

    private final List<Delta> deltas = new ArrayList<>();
    private final List<Reset> resets = new ArrayList<>();
    private final Map<String, Integer> scores = new HashMap<>();
    private final Map<String, Integer> pardonScores = new HashMap<>();
    private EloChange.Applied nextAutomatic;

    public List<Delta> deltas() {
        return this.deltas;
    }

    public List<Reset> resets() {
        return this.resets;
    }

    public void givenScore(String owner, String section, int score) {
        this.scores.put(owner + "/" + section, score);
    }

    public void givenPardonScore(String section, int pardonScore) {
        this.pardonScores.put(section, pardonScore);
    }

    /** Makes the next delta report this automatic punishment. */
    public void givenNextAutomatic(EloChange.AutomaticPunishment automatic) {
        this.nextAutomatic = new EloChange.Applied("", 0, 0, Optional.of(automatic));
    }

    @Override
    public EloChange applyDelta(String owner, String sectionKey, int delta, EloReasonCode reason, UUID sourceEvidenceId, Map<String, Object> metaData) {
        this.deltas.add(new Delta(owner, sectionKey, delta, reason, metaData));
        int before = this.scores.getOrDefault(owner + "/" + sectionKey, 1000);
        int after = before + delta;
        this.scores.put(owner + "/" + sectionKey, after);
        Optional<EloChange.AutomaticPunishment> automatic = this.nextAutomatic == null ? Optional.empty() : this.nextAutomatic.automatic();
        this.nextAutomatic = null;
        return new EloChange.Applied(sectionKey, before, after, automatic);
    }

    @Override
    public Optional<Integer> score(String owner, String sectionKey) {
        return Optional.of(this.scores.getOrDefault(owner + "/" + sectionKey, 1000));
    }

    @Override
    public Optional<Integer> resetToPardon(String owner, String sectionKey, Map<String, Object> metaData) {
        int pardon = this.pardonScores.getOrDefault(sectionKey, 700);
        this.resets.add(new Reset(owner, sectionKey, pardon));
        this.scores.put(owner + "/" + sectionKey, pardon);
        return Optional.of(pardon);
    }
}
