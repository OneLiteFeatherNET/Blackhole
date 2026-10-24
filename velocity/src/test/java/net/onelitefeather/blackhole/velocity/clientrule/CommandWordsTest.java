package net.onelitefeather.blackhole.velocity.clientrule;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandWordsTest {

    @Test
    void theFirstWordIsTheCommand() {
        assertEquals(Set.of("op"), CommandWords.candidates("op me"));
        assertEquals(Set.of("op"), CommandWords.candidates("op"));
    }

    @Test
    void aLeadingSlashAndLeadingSpacesAreRemoved() {
        assertEquals(Set.of("op"), CommandWords.candidates("/op me"));
        assertEquals(Set.of("op"), CommandWords.candidates("  op me"));
        assertEquals(Set.of("op"), CommandWords.candidates("/  op me"));
        assertEquals(Set.of("op"), CommandWords.candidates(" / \t op"));
    }

    @Test
    void theWordIsLowerCased() {
        assertEquals(Set.of("op"), CommandWords.candidates("OP me"));
        assertEquals(Set.of("worldedit:sel", "sel"), CommandWords.candidates("WorldEdit:Sel"));
    }

    @Test
    void aNamespacedWordIsMatchedAsWrittenAndAsTheBareLabel() {
        assertEquals(Set.of("minecraft:op", "op"), CommandWords.candidates("minecraft:op me"));
    }

    @Test
    void argumentsAreNeverPartOfTheWords() {
        assertEquals(Set.of("msg"), CommandWords.candidates("msg Steve op"));
    }

    @Test
    void aBlankLineHasNoWords() {
        assertEquals(Set.of(), CommandWords.candidates(""));
        assertEquals(Set.of(), CommandWords.candidates("   "));
        assertEquals(Set.of(), CommandWords.candidates("/"));
    }

    @Test
    void aTrailingColonIsNotANamespace() {
        assertEquals(Set.of("odd:"), CommandWords.candidates("odd: x"));
    }
}
