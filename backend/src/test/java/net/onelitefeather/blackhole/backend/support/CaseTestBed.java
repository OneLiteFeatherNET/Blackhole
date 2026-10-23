package net.onelitefeather.blackhole.backend.support;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.casetimeline.CaseWriter;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** A {@link CaseTimeline} over an in-memory log, a recording case writer and a meter registry a test can read. */
public final class CaseTestBed {

    /** A case writer that remembers which cases it was asked to reopen. */
    public static final class RecordingCaseWriter implements CaseWriter {

        private final List<String> reopened = new ArrayList<>();
        private RuntimeException failure;

        public List<String> reopened() {
            return this.reopened;
        }

        public void fail(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public boolean reopenIfClosed(String punishmentIdentifier, long now) {
            if (this.failure != null) {
                throw this.failure;
            }
            this.reopened.add(punishmentIdentifier);
            return true;
        }
    }

    public final InMemoryCaseEventRepository log = new InMemoryCaseEventRepository();
    public final RecordingCaseWriter writer = new RecordingCaseWriter();
    public final MeterRegistry meters = new SimpleMeterRegistry();
    public final CaseTimeline timeline;

    public CaseTestBed(Clock clock) {
        this.timeline = new CaseTimeline(this.log, this.writer, clock, this.meters);
    }

    /** How many recordings failed so far. */
    public double failures() {
        return this.meters.get(CaseTimeline.FAILURE_METER).counter().count();
    }
}
