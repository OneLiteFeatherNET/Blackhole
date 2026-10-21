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
        List<String> servers,
        int baseScore,
        Integer softThreshold,
        String softDuration,
        int permanentThreshold,
        int pardonScore,
        String permanentReason,
        int decayPerDay
) {
}
