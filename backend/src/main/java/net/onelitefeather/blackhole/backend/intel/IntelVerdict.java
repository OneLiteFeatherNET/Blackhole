package net.onelitefeather.blackhole.backend.intel;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The outcome for one login: the policy that applies, the classes that matched, and whether the owner is on the
 * allow-list. An allow-listed owner is ALLOW for every class; the classes are kept so staff can see what was exempted.
 */
public record IntelVerdict(IntelPolicy policy, Set<IntelClass> classes, boolean allowListed) {

    public IntelVerdict {
        classes = Set.copyOf(classes);
    }

    /**
     * @param policies    the stored policy per class; a class with no row is {@link IntelPolicy#FLAG}
     * @param classes     the classes the address matched; empty for a clean address
     * @param allowListed whether the owner is on the allow-list right now
     */
    public static IntelVerdict decide(Map<IntelClass, IntelPolicy> policies, Set<IntelClass> classes, boolean allowListed) {
        EnumSet<IntelClass> matched = EnumSet.noneOf(IntelClass.class);
        matched.addAll(classes);
        if (allowListed) {
            return new IntelVerdict(IntelPolicy.ALLOW, matched, true);
        }
        IntelPolicy strictest = IntelPolicy.ALLOW;
        for (IntelClass classification : matched) {
            strictest = strictest.stricterOf(policies.getOrDefault(classification, IntelPolicy.FLAG));
        }
        return new IntelVerdict(strictest, matched, false);
    }
}
