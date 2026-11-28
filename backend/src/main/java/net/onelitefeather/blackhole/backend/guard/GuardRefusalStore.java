package net.onelitefeather.blackhole.backend.guard;

import java.util.List;

/**
 * The guard refusal counters: one count per ASN for the summary, and a sliding window per ASN for the burst rule. Held
 * outside the database (Dragonfly in production); a refusal costs one write there, never a database write.
 */
public interface GuardRefusalStore {

    /** Counts one refusal for the summary and remembers the ASN's organisation and country for the message. */
    void recordRefusal(long asn, String organisation, String countryCode);

    /**
     * Adds one refusal to the ASN's window, forgets the refusals older than the window, and returns how many remain.
     */
    long addBurstRefusal(long asn, long nowMillis, long windowMillis);

    /** Reads and clears the summary counters in one step. */
    List<SummaryRow> drainSummary();

    /** One ASN's refusals since the last drain. */
    record SummaryRow(long asn, long count, String organisation, String countryCode) {
    }
}
