package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.punishment.SectionPunisher;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link SectionPunisher} that records what it was asked to do and hands out predictable identifiers.
 */
public final class RecordingSectionPunisher implements SectionPunisher {

    /** One automatic punishment request. */
    public record Call(String kind, String owner, String section, Duration duration, Map<String, Object> metaData) {
    }

    private final List<Call> calls = new ArrayList<>();
    private final Set<String> activePermanent = new HashSet<>();

    /** Marks the owner as holding an active permanent punishment in the section. */
    public void givenActivePermanent(String owner, String section) {
        this.activePermanent.add(owner + "/" + section);
    }

    public List<Call> calls() {
        return this.calls;
    }

    @Override
    public boolean hasActivePermanent(String owner, String sectionKey) {
        return this.activePermanent.contains(owner + "/" + sectionKey);
    }

    @Override
    public Optional<String> applyPermanent(String owner, SectionDTO.Response section, Map<String, Object> extraMetaData) {
        this.calls.add(new Call("PERMANENT", owner, section.key(), null, extraMetaData));
        this.activePermanent.add(owner + "/" + section.key());
        return Optional.of("permanent-" + this.calls.size());
    }

    @Override
    public Optional<String> applySoft(String owner, SectionDTO.Response section, Duration duration, Map<String, Object> extraMetaData) {
        this.calls.add(new Call("SOFT", owner, section.key(), duration, extraMetaData));
        return Optional.of("soft-" + this.calls.size());
    }
}
