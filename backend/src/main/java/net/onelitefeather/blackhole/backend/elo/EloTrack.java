package net.onelitefeather.blackhole.backend.elo;

/**
 * The two independent ELO tracks. A player can play without chatting (or vice versa), so each
 * track must be able to trigger consequences on its own - a silent cheater must be caught by
 * the gameplay track even with zero chat activity, and a toxic-but-legitimate player must be
 * caught by the chat track even with clean gameplay.
 */
public enum EloTrack {

    CHAT,
    GAMEPLAY
}
