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
    /** What {@link #generate} answers; a stored version 1 by default. */
    public Result answer = new Result(Outcome.STORED, 1, UUID.randomUUID(), 1);
    /** What {@link #isCurrent} answers. */
    public boolean current = true;

    @Override
    public Result generate(UUID appealId, boolean force) {
        this.generated.add(appealId + ":" + force);
        if (this.failure != null) {
            throw this.failure;
        }
        return this.answer;
    }

    @Override
    public boolean isCurrent(UUID appealId) {
        return this.current;
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
