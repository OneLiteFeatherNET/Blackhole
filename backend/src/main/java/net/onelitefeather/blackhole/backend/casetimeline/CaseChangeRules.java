package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The rules of a staff change of a case, with no I/O: tag normalisation and the state a change leaves behind. The single edit
 * and the bulk edit both use them, so one case gets the same rules either way. The assignee must be a known human staff member;
 * that check reads the staff table and stays with the service.
 */
public final class CaseChangeRules {

    private CaseChangeRules() {
    }

    /** The sorted unique tags, or {@code null} when a tag is no slug or there are too many. */
    public static @Nullable List<String> normalizeTags(List<String> tags) {
        TreeSet<String> unique = new TreeSet<>();
        for (String tag : tags) {
            if (tag == null || !CaseTags.VALID.matcher(tag).matches()) {
                return null;
            }
            unique.add(tag);
        }
        return unique.size() > CaseTags.MAX ? null : new ArrayList<>(unique);
    }

    /**
     * The state after a single edit: each named field replaces the current one, and {@code tags}, when given, is the full new list.
     * An absent field keeps its value; {@code clearAssignee} removes the assignee.
     */
    public static CaseMeta replace(CaseMeta before, @Nullable String status, @Nullable UUID assigneeOid, boolean clearAssignee,
                                   @Nullable String priority, @Nullable List<String> tags) {
        return new CaseMeta(
                status != null ? status : before.status(),
                clearAssignee ? null : assigneeOid != null ? assigneeOid : before.assignee(),
                priority != null ? priority : before.priority(),
                tags != null ? tags : before.tags());
    }

    /**
     * The state after a bulk edit: the named status, assignee and priority replace the current ones, and the tags are added to and
     * removed from the current set.
     *
     * @param addTags    slugs already validated by {@link #normalizeTags}
     * @param removeTags slugs already validated by {@link #normalizeTags}
     * @return the new state, or {@code null} when the result would hold more than {@link CaseTags#MAX} tags
     */
    public static @Nullable CaseMeta adjust(CaseMeta before, @Nullable String status, @Nullable UUID assigneeOid, boolean clearAssignee,
                                            @Nullable String priority, List<String> addTags, List<String> removeTags) {
        TreeSet<String> tags = new TreeSet<>(before.tags());
        tags.addAll(addTags);
        tags.removeAll(removeTags);
        if (tags.size() > CaseTags.MAX) {
            return null;
        }
        return replace(before, status, assigneeOid, clearAssignee, priority, new ArrayList<>(tags));
    }
}
