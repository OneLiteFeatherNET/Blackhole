package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.elo.EloScoreEntity;
import net.onelitefeather.blackhole.backend.elo.EloScoreId;
import net.onelitefeather.blackhole.backend.elo.EloScoreRepository;
import net.onelitefeather.blackhole.backend.elo.EloScoreWriter;

/**
 * The semantics of the SQL upserts over an in-memory score repository, for unit tests.
 */
public final class InMemoryEloScoreWriter implements EloScoreWriter {

    private final EloScoreRepository repository;

    public InMemoryEloScoreWriter(EloScoreRepository repository) {
        this.repository = repository;
    }

    @Override
    public synchronized int addDelta(String owner, String sectionKey, int baseScore, int delta, long now) {
        EloScoreEntity score = this.repository.findById(new EloScoreId(owner, sectionKey))
                .orElseGet(() -> new EloScoreEntity(owner, sectionKey, baseScore, now));
        score.setScore(score.getScore() + delta);
        score.setUpdatedAt(now);
        this.repository.save(score);
        return score.getScore();
    }

    @Override
    public synchronized void setScore(String owner, String sectionKey, int score, long now) {
        this.repository.save(new EloScoreEntity(owner, sectionKey, score, now));
    }

    @Override
    public synchronized boolean recover(String owner, String sectionKey, int recovery, long expectedUpdatedAt, long now) {
        EloScoreEntity score = this.repository.findById(new EloScoreId(owner, sectionKey)).orElse(null);
        if (score == null || score.getUpdatedAt() != expectedUpdatedAt) {
            return false;
        }
        score.setScore(score.getScore() + recovery);
        score.setUpdatedAt(now);
        this.repository.save(score);
        return true;
    }
}
