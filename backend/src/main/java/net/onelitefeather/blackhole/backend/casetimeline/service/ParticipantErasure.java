package net.onelitefeather.blackhole.backend.casetimeline.service;

/**
 * Erases the conversation participants of a case that identify a person (the bound player of a Discord ticket), for the player-side erase of
 * the conversation. Does not throw for "nothing to erase".
 */
public interface ParticipantErasure {

    /** Nothing to erase; for flows built without the Discord ticket store. */
    ParticipantErasure NONE = punishmentIdentifier -> 0;

    /**
     * Erases every player participant of the punishment's case (Discord id and name go, the timeline keeps its structure) and has the stored
     * protocols purged and regenerated.
     *
     * @return how many participants were erased now
     */
    int erasePlayerParticipants(String punishmentIdentifier);
}
