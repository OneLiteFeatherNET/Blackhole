package net.onelitefeather.blackhole.backend.security;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.security.token.DefaultRolesFinder;
import io.micronaut.security.token.RolesFinder;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps token claims to the internal {@link Roles}.
 *
 * <p>The token kind selects the claim: a delegated token (it carries {@code scp}) is read from the
 * human claim and can only be granted ADMIN, MODERATOR, VIEWER or METRICS; an app-only token is read
 * from the machine claim and can only be granted PROXY, METRICS or DISCORD. A value in the claim of the
 * other kind grants nothing. ADMIN implies MODERATOR implies VIEWER.
 *
 * <p>A delegated token whose group list was replaced by an overage marker has no role: the groups
 * are not resolved through Microsoft Graph.
 */
@Singleton
@Replaces(DefaultRolesFinder.class)
public class BlackholeRolesFinder implements RolesFinder {

    private static final Logger LOG = LoggerFactory.getLogger(BlackholeRolesFinder.class);

    static final String SCOPE_CLAIM = "scp";
    static final String GROUPS_CLAIM = "groups";
    static final String OBJECT_ID_CLAIM = "oid";

    private final RoleMappingProperties mapping;

    public BlackholeRolesFinder(RoleMappingProperties mapping) {
        this.mapping = mapping;
    }

    @Override
    public List<String> resolveRoles(Map<String, Object> claims) {
        if (claims == null) {
            return List.of();
        }
        if (isDelegated(claims)) {
            if (isGroupsOverage(claims)) {
                LOG.warn("Token of oid {} carries a groups overage marker instead of groups: no role granted",
                        claims.get(OBJECT_ID_CLAIM));
                return List.of();
            }
            Set<String> claimValues = values(claims.get(this.mapping.claimHuman()));
            Set<String> roles = new LinkedHashSet<>();
            grant(roles, claimValues, this.mapping.admin(), Roles.ADMIN);
            grant(roles, claimValues, this.mapping.moderator(), Roles.MODERATOR);
            grant(roles, claimValues, this.mapping.viewer(), Roles.VIEWER);
            grant(roles, claimValues, this.mapping.metrics(), Roles.METRICS);
            return List.copyOf(roles);
        }
        Set<String> claimValues = values(claims.get(this.mapping.claimMachine()));
        Set<String> roles = new LinkedHashSet<>();
        grant(roles, claimValues, this.mapping.machineProxy(), Roles.PROXY);
        grant(roles, claimValues, this.mapping.machineMetrics(), Roles.METRICS);
        grant(roles, claimValues, this.mapping.machineDiscord(), Roles.DISCORD);
        return List.copyOf(roles);
    }

    /** Whether the token was issued on behalf of a signed-in user (it carries a delegated scope). */
    public static boolean isDelegated(Map<String, Object> claims) {
        return claims.containsKey(SCOPE_CLAIM);
    }

    /**
     * Whether a delegated token's group list was dropped by Entra (more than 200 groups): the
     * groups claim is absent and the token carries {@code hasgroups} or a {@code _claim_names} entry.
     */
    public boolean isGroupsOverage(Map<String, Object> claims) {
        if (!isDelegated(claims) || !GROUPS_CLAIM.equals(this.mapping.claimHuman()) || claims.containsKey(GROUPS_CLAIM)) {
            return false;
        }
        if (Boolean.parseBoolean(String.valueOf(claims.get("hasgroups")))) {
            return true;
        }
        return claims.get("_claim_names") instanceof Map<?, ?> names && names.containsKey(GROUPS_CLAIM);
    }

    private static void grant(Set<String> roles, Set<String> claimValues, List<String> accepted, String role) {
        for (String value : accepted) {
            if (!value.isBlank() && claimValues.contains(value.trim())) {
                roles.addAll(Roles.expand(role));
                return;
            }
        }
    }

    private static Set<String> values(Object claim) {
        Set<String> values = new LinkedHashSet<>();
        if (claim instanceof Collection<?> collection) {
            collection.forEach(value -> values.add(String.valueOf(value)));
        } else if (claim instanceof String single && !single.isBlank()) {
            values.add(single);
        }
        return values;
    }
}
