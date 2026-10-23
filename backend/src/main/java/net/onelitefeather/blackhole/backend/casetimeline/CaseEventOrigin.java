package net.onelitefeather.blackhole.backend.casetimeline;

/** How a case event came to be: written when it happened, backfilled by the migration, or repaired by the reconciler. */
public enum CaseEventOrigin {
    LIVE,
    BACKFILLED,
    RECONSTRUCTED
}
