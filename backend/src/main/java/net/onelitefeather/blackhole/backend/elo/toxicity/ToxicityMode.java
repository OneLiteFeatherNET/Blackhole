package net.onelitefeather.blackhole.backend.elo.toxicity;

/**
 * How chat toxicity scores are used. {@code OFF} never calls the scorer, {@code SHADOW} counts and logs
 * would-be flags without any ELO change, {@code ENFORCE} applies the flag.
 */
public enum ToxicityMode {
    OFF,
    SHADOW,
    ENFORCE
}
