package net.onelitefeather.blackhole.velocity.clientrule;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The command words the rule editor can suggest: the literal children of the command tree the proxy sends
 * to its players (what exists, including the commands of backend servers and namespaced forms such as
 * {@code minecraft:op}). It is built from the tree only, never from what a player typed, so a mistyped
 * secret cannot reach it.
 */
public final class CommandCatalogue {

    static final int MAX_WORDS = 5000;
    private static final Pattern WORD = Pattern.compile("[a-z0-9._:/@+-]{1,128}");

    private final Set<String> words = ConcurrentHashMap.newKeySet();

    /**
     * Learns the literal children of a command tree.
     *
     * @return whether a word was new
     */
    public boolean learn(@NotNull RootCommandNode<?> root) {
        boolean added = false;
        for (CommandNode<?> child : root.getChildren()) {
            if (child instanceof LiteralCommandNode<?> literal && this.words.size() < MAX_WORDS) {
                String word = literal.getLiteral().toLowerCase(Locale.ROOT);
                if (WORD.matcher(word).matches() && this.words.add(word)) {
                    added = true;
                }
            }
        }
        return added;
    }

    /** @return the words learned so far, sorted */
    public @NotNull Set<String> words() {
        return new TreeSet<>(this.words);
    }
}
