package net.onelitefeather.blackhole.backend.casetimeline;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The case event log is append-only: the only things that may change a row are a note redaction and
 * the cascade with its punishment. These tests fail when a write path other than those is added.
 */
class CaseEventRepositorySurfaceTest {

    private static Set<String> publicMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
                .map(Method::getName)
                .collect(TreeSet::new, Set::add, Set::addAll);
    }

    @Test
    void theRepositoryInterfaceOffersOnlyTheDocumentedWritePaths() {
        assertEquals(Set.of("append", "clearStaffNameSnapshots", "deleteForPunishment", "redact"), publicMethods(CaseEventRepository.class),
                "a new write path on the case event log needs a design decision (staff erasure, design 4.8), not just a method");
    }

    @Test
    void theJdbcRepositoryOffersNothingBeyondTheInterface() {
        assertEquals(Set.of("append", "clearStaffNameSnapshots", "deleteForPunishment", "redact"), publicMethods(JdbcCaseEventRepository.class),
                "the implementation must not smuggle in another mutation");
    }

    @Test
    void theReaderOnlyReads() {
        assertEquals(Set.of("timeline"), publicMethods(CaseEventReader.class));
        assertEquals(Set.of("timeline"), publicMethods(JdbcCaseEventReader.class));
    }
}
