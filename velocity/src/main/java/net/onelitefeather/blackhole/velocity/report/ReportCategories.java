package net.onelitefeather.blackhole.velocity.report;

import net.onelitefeather.blackhole.client.model.ReportCategory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The reasons a player can pick for {@code /report}, mapped to the backend's report categories. The
 * words offered by tab completion are the canonical ones; a few aliases (including German ones) are
 * accepted as well.
 */
public final class ReportCategories {

    private static final Map<String, ReportCategory> CANONICAL = Map.of(
            "chat", ReportCategory.CHAT_ABUSE,
            "cheating", ReportCategory.CHEATING,
            "griefing", ReportCategory.GRIEFING,
            "other", ReportCategory.OTHER);

    private static final Map<String, ReportCategory> ALIASES = Map.of(
            "chat_abuse", ReportCategory.CHAT_ABUSE,
            "cheat", ReportCategory.CHEATING,
            "hacks", ReportCategory.CHEATING,
            "grief", ReportCategory.GRIEFING,
            "sonstiges", ReportCategory.OTHER);

    private ReportCategories() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** @return the words tab completion offers, in the order they are documented */
    public static @NotNull List<String> words() {
        return List.of("chat", "cheating", "griefing", "other");
    }

    /** @return the words that start with the typed prefix, ignoring case */
    public static @NotNull List<String> suggest(@NotNull String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return words().stream().filter(word -> word.startsWith(lower)).toList();
    }

    /** @return the category the typed word stands for, empty if it is none of them */
    public static @NotNull Optional<ReportCategory> parse(@NotNull String input) {
        String lower = input.strip().toLowerCase(Locale.ROOT);
        ReportCategory category = CANONICAL.get(lower);
        return Optional.ofNullable(category != null ? category : ALIASES.get(lower));
    }

    /** @return the key of the localized display name of the category */
    public static @NotNull String nameKey(@NotNull ReportCategory category) {
        return switch (category) {
            case CHAT_ABUSE -> "report.category.chat";
            case CHEATING -> "report.category.cheating";
            case GRIEFING -> "report.category.griefing";
            case OTHER -> "report.category.other";
        };
    }
}
