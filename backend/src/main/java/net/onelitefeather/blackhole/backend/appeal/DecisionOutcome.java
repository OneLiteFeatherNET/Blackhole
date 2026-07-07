package net.onelitefeather.blackhole.backend.appeal;

public enum DecisionOutcome {

    APPLIED,

    /** The punishment already expired or was otherwise lifted naturally - nothing left to lift/reduce. */
    PUNISHMENT_NOT_ACTIVE
}
