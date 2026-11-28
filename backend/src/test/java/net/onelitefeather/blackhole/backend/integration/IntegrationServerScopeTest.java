package net.onelitefeather.blackhole.backend.integration;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntegrationServerScopeTest {

    static Stream<Arguments> labels() {
        return Stream.of(
                Arguments.of(Named.of("dynamic service of an allowed task", "Lobby-2"), List.of("Lobby"), List.of(), true),
                Arguments.of(Named.of("service of another task", "Survival-1"), List.of("Lobby"), List.of(), false),
                Arguments.of(Named.of("prefix lookalike of the task", "Lobbyx-2"), List.of("Lobby"), List.of(), false),
                Arguments.of(Named.of("task name without a number", "Lobby"), List.of("Lobby"), List.of(), false),
                Arguments.of(Named.of("non digit number", "Lobby-x"), List.of("Lobby"), List.of(), false),
                Arguments.of(Named.of("empty number", "Lobby-"), List.of("Lobby"), List.of(), false),
                Arguments.of(Named.of("static server by exact name", "Creative"), List.of(), List.of("Creative"), true),
                Arguments.of(Named.of("static server with a number suffix is another server", "Creative-2"), List.of(), List.of("Creative"), false),
                Arguments.of(Named.of("static server allowed beside a task", "Creative"), List.of("Lobby"), List.of("Creative"), true),
                Arguments.of(Named.of("nothing allowed", "Lobby-2"), List.of(), List.of(), false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("labels")
    void theLabelIsAllowedOnlyInsideTheRegistrationScope(String label, List<String> tasks, List<String> statics, boolean expected) {
        assertEquals(expected, IntegrationServerScope.allows(label, tasks, statics),
                "label '" + label + "' with tasks " + tasks + " and static servers " + statics);
    }

    @ParameterizedTest(name = "null or empty label is refused")
    @MethodSource("nullOrEmpty")
    void aMissingLabelIsNeverInScope(String label) {
        assertEquals(false, IntegrationServerScope.allows(label, List.of("Lobby"), List.of("Creative")));
    }

    static Stream<String> nullOrEmpty() {
        return Stream.of(null, "");
    }
}
