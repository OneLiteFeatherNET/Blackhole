package net.onelitefeather.blackhole.backend.report;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CategorySectionMappingTest {

    @Test
    void aConfiguredCategoryMapsToItsSection() {
        CategorySectionMapping mapping = new CategorySectionMapping(Map.of("CHEATING", "network"));

        assertEquals(Optional.of("network"), mapping.sectionFor(ReportCategory.CHEATING));
    }

    @Test
    void categoryNamesAreMatchedIgnoringCaseAndSeparators() {
        CategorySectionMapping mapping = new CategorySectionMapping(Map.of("chat-abuse", "chat"));

        assertEquals(Optional.of("chat"), mapping.sectionFor(ReportCategory.CHAT_ABUSE));
    }

    @Test
    void aBlankSectionMeansTheCategoryHasNoEloEffect() {
        CategorySectionMapping mapping = new CategorySectionMapping(Map.of("OTHER", ""));

        assertEquals(Optional.empty(), mapping.sectionFor(ReportCategory.OTHER));
    }

    @Test
    void anUnmappedCategoryHasNoEloEffect() {
        CategorySectionMapping mapping = new CategorySectionMapping(Map.of("CHEATING", "network"));

        assertEquals(Optional.empty(), mapping.sectionFor(ReportCategory.GRIEFING));
    }

    @Test
    void withoutAnyConfigurationNoCategoryScores() {
        CategorySectionMapping mapping = new CategorySectionMapping(null);

        assertEquals(Optional.empty(), mapping.sectionFor(ReportCategory.CHEATING));
    }
}
