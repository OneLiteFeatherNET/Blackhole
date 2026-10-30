package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.velocity.guard.GuardRefusal;
import org.jetbrains.annotations.NotNull;

/**
 * What a staff member reads when the backend refuses a join guard or area ban change. Translated, like the
 * punishment errors: the texts live in the message bundles.
 */
public final class GuardMessages {

    private GuardMessages() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param refusal why the backend refused the change
     * @return the translated line that tells the staff member what to do about it
     */
    public static @NotNull Component refusal(@NotNull GuardRefusal refusal) {
        return switch (refusal) {
            case STAFF_ROLE_STALE -> Component.translatable("guard.error.staff_role_stale");
            case STAFF_NOT_ELIGIBLE -> Component.translatable("guard.error.staff_not_eligible");
            case FORBIDDEN_KIND -> Component.translatable("guard.error.forbidden_kind");
            case FORBIDDEN_DURATION -> Component.translatable("guard.error.forbidden_duration");
            case CONFLICT -> Component.translatable("guard.error.conflict");
            case VALIDATION -> Component.translatable("guard.error.validation");
            case OTHER -> Component.translatable("guard.error.failed");
        };
    }
}
