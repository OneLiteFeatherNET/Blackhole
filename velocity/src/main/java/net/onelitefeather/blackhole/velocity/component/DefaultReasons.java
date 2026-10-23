package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * The English reasons the backend stores itself on punishments the ELO system applies. Only these
 * exact texts are replaced by a built-in translation; any other stored reason (for example an
 * admin-customised section reason) is shown as stored.
 */
final class DefaultReasons {

    /**
     * Seeded permanent reasons of the sections {@code chat} and {@code network}, from
     * {@code backend/src/main/resources/db/migration/V2__punishment_sections.sql} (no later migration changes them).
     */
    private static final Set<String> PERMANENT = Set.of(
            "Permanently muted after repeated chat violations",
            "Permanently banned after repeated violations");

    /**
     * {@code SOFT_REASON} in the backend's {@code PunishmentApplicationService}, the reason of every
     * automatic temporary punishment.
     */
    private static final String TEMPORARY = "Automatic temporary punishment (ELO system)";

    /**
     * @return the translated reason if the punishment is automatic and carries a seeded default text, else
     *         {@code null}, meaning the stored reason is shown
     */
    static @Nullable Component translated(boolean automatic, boolean permanent, @Nullable String storedReason) {
        if (!automatic || storedReason == null) {
            return null;
        }
        if (permanent ? PERMANENT.contains(storedReason) : TEMPORARY.equals(storedReason)) {
            return Component.translatable(permanent ? "punishment.reason.escalated" : "punishment.reason.automatic");
        }
        return null;
    }

    private DefaultReasons() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
