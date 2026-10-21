package net.onelitefeather.blackhole.backend.report;

import io.micronaut.context.annotation.Property;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which punishment section a report category scores in, from {@code blackhole.elo.report.category-sections}.
 * A category mapped to a blank value, or not mapped at all, has no section and so no ELO effect.
 * Category names are compared ignoring case and separators ({@code CHAT_ABUSE}, {@code chat-abuse}).
 */
@Singleton
public class CategorySectionMapping {

    private final Map<String, String> sectionByCategory = new HashMap<>();

    public CategorySectionMapping(
            @Nullable @Property(name = "blackhole.elo.report.category-sections") Map<String, String> configured
    ) {
        if (configured != null) {
            configured.forEach((category, section) -> this.sectionByCategory.put(normalize(category), section == null ? "" : section.trim()));
        }
    }

    /**
     * @return the section key the category scores in, or empty if the category has no ELO effect
     */
    public Optional<String> sectionFor(ReportCategory category) {
        String section = this.sectionByCategory.get(normalize(category.name()));
        return section == null || section.isBlank() ? Optional.empty() : Optional.of(section);
    }

    private static String normalize(String category) {
        return category.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }
}
