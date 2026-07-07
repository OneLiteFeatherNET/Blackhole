package net.onelitefeather.blackhole.backend.appeal;

import java.util.Map;

/**
 * @param eligible          overall gate result - both hard checks must pass
 * @param severe            whether the punishment is SEVERE (structurally caps the reviewer to
 *                           duration-reduction, never a full lift, enforced by
 *                           {@code AppealController})
 * @param checklistSnapshot the full checklist result, stored verbatim on the appeal for audit
 */
public record EligibilityResult(boolean eligible, boolean severe, Map<String, Object> checklistSnapshot) {
}
