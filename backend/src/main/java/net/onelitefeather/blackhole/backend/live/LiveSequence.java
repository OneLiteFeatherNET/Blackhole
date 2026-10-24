package net.onelitefeather.blackhole.backend.live;

/** The current seq of a case as one audience may see it; read when a stream opens, so {@code ready} and the resume are right. */
public interface LiveSequence {

    long current(String caseKey, LiveAudience audience);
}
