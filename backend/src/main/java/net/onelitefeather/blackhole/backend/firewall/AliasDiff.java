package net.onelitefeather.blackhole.backend.firewall;

import java.util.Set;
import java.util.TreeSet;

/**
 * What a reconciliation has to write: the desired ranges missing from the alias, and the alias entries that the
 * database no longer wants. Both sets hold canonical text, so an identical alias yields an empty diff.
 */
public record AliasDiff(Set<String> toAdd, Set<String> toDelete) {

    public static AliasDiff of(Set<String> desired, Set<String> current) {
        Set<String> add = new TreeSet<>(desired);
        add.removeAll(current);
        Set<String> delete = new TreeSet<>(current);
        delete.removeAll(desired);
        return new AliasDiff(add, delete);
    }

    public boolean isEmpty() {
        return this.toAdd.isEmpty() && this.toDelete.isEmpty();
    }

    public int size() {
        return this.toAdd.size() + this.toDelete.size();
    }
}
