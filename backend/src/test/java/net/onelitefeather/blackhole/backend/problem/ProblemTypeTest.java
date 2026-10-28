package net.onelitefeather.blackhole.backend.problem;

import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProblemTypeTest {

    private static final String BASE = "https://blackhole.onelitefeather.net/problems/";

    @Test
    void theBaseUriIsTheBlackholeProblemsPath() {
        assertEquals(BASE, ProblemType.BASE_URI, "the problem type base URI");
    }

    @Test
    void aCodeGivesItsKebabCaseSlug() {
        assertEquals(BASE + "not-eligible", ProblemType.forCode("NOT_ELIGIBLE"), "NOT_ELIGIBLE becomes not-eligible");
    }

    @Test
    void aMultiWordCodeGivesItsKebabCaseSlug() {
        assertEquals(BASE + "transcript-mismatch", ProblemType.forCode("TRANSCRIPT_MISMATCH"), "TRANSCRIPT_MISMATCH becomes transcript-mismatch");
    }

    @ParameterizedTest
    @CsvSource({
            "VALIDATION,validation",
            "NOT_FOUND,not-found",
            "CONFLICT,conflict",
            "UNAUTHORIZED,unauthorized",
            "FORBIDDEN,forbidden",
            "UNAVAILABLE,unavailable"
    })
    void theStatusKindGivesTheSlugWhenThereIsNoCode(ErrorKind kind, String slug) {
        assertEquals(BASE + slug, ProblemType.forKind(kind), "the slug of kind " + kind);
    }

    @ParameterizedTest
    @EnumSource(ErrorKind.class)
    void everyErrorKindGivesItsKebabCaseName(ErrorKind kind) {
        String expected = BASE + kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
        assertEquals(expected, ProblemType.forKind(kind), "the type of kind " + kind + " is its kebab-case name");
    }
}
