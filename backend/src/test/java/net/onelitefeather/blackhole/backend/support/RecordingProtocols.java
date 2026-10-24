package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.casetimeline.protocol.AppealProtocolGenerator;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolRedaction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A protocol generator and redaction hook that only remember how they were called. */
public final class RecordingProtocols implements AppealProtocolGenerator, ProtocolRedaction {

    /** The appeals a protocol was generated for, with the force flag, in call order. */
    public final List<String> generated = new ArrayList<>();
    public final List<String> redacted = new ArrayList<>();
    public final List<UUID> anonymized = new ArrayList<>();
    public RuntimeException failure;

    @Override
    public Result generate(UUID appealId, boolean force) {
        this.generated.add(appealId + ":" + force);
        if (this.failure != null) {
            throw this.failure;
        }
        return Result.of(Outcome.STORED);
    }

    @Override
    public void afterRedaction(String punishmentIdentifier) {
        this.redacted.add(punishmentIdentifier);
    }

    @Override
    public void afterParticipantErased(String punishmentIdentifier) {
        this.redacted.add(punishmentIdentifier);
    }

    @Override
    public void afterStaffAnonymized(UUID actorOid) {
        this.anonymized.add(actorOid);
    }
}
