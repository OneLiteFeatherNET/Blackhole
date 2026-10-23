package net.onelitefeather.blackhole.backend.casetimeline;

import java.util.List;

/** The read side of the case event log. */
public interface CaseEventReader {

    /** Every event of the punishment in timeline order ({@code occurredAt}, then {@code seq}). */
    List<CaseEventRecord> timeline(String punishmentIdentifier);
}
