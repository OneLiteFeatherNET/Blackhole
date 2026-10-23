package net.onelitefeather.blackhole.velocity.report;

import net.onelitefeather.blackhole.client.model.ReportCategory;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportCategoriesTest {

    @Test
    void everyOfferedWordMapsToACategoryAndTogetherTheyCoverTheBackendsCategories() {
        Set<ReportCategory> covered = ReportCategories.words().stream()
                .map(word -> ReportCategories.parse(word).orElseThrow())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ReportCategory.class)));

        assertEquals(EnumSet.allOf(ReportCategory.class), covered, "a backend category without a word could never be reported");
    }

    @Test
    void parsingIgnoresCaseAndSurroundingSpace() {
        assertEquals(Optional.of(ReportCategory.CHEATING), ReportCategories.parse("  CheAting "));
    }

    @Test
    void aliasesAreAccepted() {
        assertEquals(Optional.of(ReportCategory.CHAT_ABUSE), ReportCategories.parse("chat_abuse"));
        assertEquals(Optional.of(ReportCategory.OTHER), ReportCategories.parse("sonstiges"));
    }

    @Test
    void anUnknownWordIsEmpty() {
        assertEquals(Optional.empty(), ReportCategories.parse("fly"));
    }

    @Test
    void suggestionsFilterByPrefixIgnoringCase() {
        assertEquals(List.of("cheating"), ReportCategories.suggest("CHE"));
        assertEquals(List.of("chat", "cheating"), ReportCategories.suggest("ch"));
        assertEquals(4, ReportCategories.suggest("").size());
    }
}
