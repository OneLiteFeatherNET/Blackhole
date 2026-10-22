package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Whether an evidence item still supports a current punishment. Stored as the ordinal in a
 * {@code smallint} column. Only {@link #VALID} items are counted and listed by default.
 */
@Serdeable
public enum EvidenceStatus {

    /** The punishment it belongs to stands (or the item is still pending a punishment). */
    VALID,

    /** The punishment was revoked by staff. The evidence is kept. */
    REVOKED,

    /** The punishment was lifted by a granted full-lift appeal. The evidence is kept. */
    LIFTED
}
