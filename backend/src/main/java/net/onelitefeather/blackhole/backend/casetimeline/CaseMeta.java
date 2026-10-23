package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The staff metadata of a case.
 *
 * @param status   {@code OPEN}, {@code IN_REVIEW}, {@code WAITING} or {@code CLOSED}
 * @param assignee the {@code oid} of the assigned staff member, if any
 * @param priority {@code LOW}, {@code NORMAL}, {@code HIGH} or {@code URGENT}
 * @param tags     sorted and unique
 */
public record CaseMeta(String status, @Nullable UUID assignee, String priority, List<String> tags) {

    public static final String DEFAULT_STATUS = "OPEN";
    public static final String DEFAULT_PRIORITY = "NORMAL";

    /** What a punishment without a case row reads as. */
    public static CaseMeta defaults() {
        return new CaseMeta(DEFAULT_STATUS, null, DEFAULT_PRIORITY, List.of());
    }

    /** One event payload per field that differs from {@code before}, in the order status, assignee, priority, tags. */
    public List<CaseEventPayload> diffFrom(CaseMeta before) {
        List<CaseEventPayload> payloads = new ArrayList<>();
        if (!before.status.equals(this.status)) {
            payloads.add(new CaseEventPayload.StatusChanged(before.status, this.status));
        }
        if (!Objects.equals(before.assignee, this.assignee)) {
            payloads.add(new CaseEventPayload.AssigneeChanged(
                    before.assignee == null ? null : before.assignee.toString(), this.assignee == null ? null : this.assignee.toString()));
        }
        if (!before.priority.equals(this.priority)) {
            payloads.add(new CaseEventPayload.PriorityChanged(before.priority, this.priority));
        }
        if (!before.tags.equals(this.tags)) {
            payloads.add(new CaseEventPayload.TagsChanged(
                    this.tags.stream().filter(tag -> !before.tags.contains(tag)).sorted().toList(),
                    before.tags.stream().filter(tag -> !this.tags.contains(tag)).sorted().toList()));
        }
        return payloads;
    }
}
