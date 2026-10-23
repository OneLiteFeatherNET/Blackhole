package net.onelitefeather.blackhole.backend.section;

import java.util.List;

/**
 * The editable fields of a section as one value, so validation and persistence do not depend on
 * which request variant (create or update) carried them.
 */
public record SectionSpec(
        String key,
        String displayName,
        EnforcementKind enforcement,
        ServerTargetKind targetKind,
        List<String> servers,
        int baseScore,
        Integer softThreshold,
        String softDuration,
        int permanentThreshold,
        int pardonScore,
        String permanentReason,
        int decayPerDay
) {

    public SectionSpec {
        targetKind = targetKind == null ? ServerTargetKind.PATTERN : targetKind;
    }

    /** A spec whose servers are glob patterns, the {@link ServerTargetKind#PATTERN} default. */
    public SectionSpec(String key, String displayName, EnforcementKind enforcement, List<String> servers,
                       int baseScore, Integer softThreshold, String softDuration, int permanentThreshold,
                       int pardonScore, String permanentReason, int decayPerDay) {
        this(key, displayName, enforcement, ServerTargetKind.PATTERN, servers, baseScore, softThreshold,
                softDuration, permanentThreshold, pardonScore, permanentReason, decayPerDay);
    }
}
