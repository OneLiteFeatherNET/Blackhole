package net.onelitefeather.blackhole.backend.security;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Claim-to-role mapping as a table: pure logic, no application context and no clock. */
class BlackholeRolesFinderTest {

    private static final String ADMIN = "group-admin";
    private static final String MODERATOR = "group-moderator";
    private static final String VIEWER = "group-viewer";
    private static final String METRICS = "group-metrics";

    private static final RoleMappingProperties GROUPS = new RoleMappingProperties(
            "groups", "roles", List.of(ADMIN), List.of(MODERATOR), List.of(VIEWER), List.of(METRICS),
            List.of("Blackhole.Proxy"), List.of("Blackhole.Metrics"), List.of("Blackhole.Discord"));

    /** The upgrade path: the human claim switched to app roles by configuration only. */
    private static final RoleMappingProperties APP_ROLES = new RoleMappingProperties(
            "roles", "roles", List.of("Blackhole.Admin"), List.of("Blackhole.Moderator"),
            List.of("Blackhole.Viewer"), List.of("Blackhole.Ops"),
            List.of("Blackhole.Proxy"), List.of("Blackhole.Metrics"), List.of("Blackhole.Discord"));

    private static Arguments scenario(String name, RoleMappingProperties mapping, Map<String, Object> claims, String... expected) {
        return Arguments.of(Named.of(name, mapping), claims, Set.of(expected));
    }

    private static Map<String, Object> delegated(String claim, Object value) {
        return Map.of("scp", "access_as_user", claim, value);
    }

    static Stream<Arguments> scenarios() {
        return Stream.of(
                scenario("admin group grants the whole hierarchy", GROUPS,
                        delegated("groups", List.of(ADMIN)), "ADMIN", "MODERATOR", "VIEWER"),
                scenario("moderator group grants moderator and viewer", GROUPS,
                        delegated("groups", List.of(MODERATOR)), "MODERATOR", "VIEWER"),
                scenario("viewer group grants viewer only", GROUPS,
                        delegated("groups", List.of(VIEWER)), "VIEWER"),
                scenario("metrics group grants metrics and nothing implied", GROUPS,
                        delegated("groups", List.of(METRICS)), "METRICS"),
                scenario("several groups add up", GROUPS,
                        delegated("groups", List.of(VIEWER, METRICS, "unrelated")), "VIEWER", "METRICS"),
                scenario("a single string value works like a one element list", GROUPS,
                        delegated("groups", VIEWER), "VIEWER"),
                scenario("groups without a mapped id grant nothing", GROUPS,
                        delegated("groups", List.of("someone-elses-group"))),
                scenario("a delegated token without groups grants nothing", GROUPS,
                        Map.of("scp", "access_as_user")),
                scenario("overage marker hasgroups grants nothing", GROUPS,
                        Map.of("scp", "access_as_user", "hasgroups", true)),
                scenario("overage marker _claim_names grants nothing", GROUPS,
                        Map.of("scp", "access_as_user", "_claim_names", Map.of("groups", "src1"))),
                scenario("app-only proxy role grants proxy", GROUPS,
                        Map.of("roles", List.of("Blackhole.Proxy")), "PROXY"),
                scenario("app-only metrics role grants metrics", GROUPS,
                        Map.of("roles", List.of("Blackhole.Metrics")), "METRICS"),
                scenario("app-only discord role grants discord and nothing implied", GROUPS,
                        Map.of("roles", List.of("Blackhole.Discord")), "DISCORD"),
                scenario("delegated token with the discord value in roles grants nothing", GROUPS,
                        Map.of("scp", "access_as_user", "roles", List.of("Blackhole.Discord"))),
                scenario("delegated token with the discord value in groups grants nothing", GROUPS,
                        delegated("groups", List.of("Blackhole.Discord"))),
                scenario("app-only token with a human role value in roles grants nothing", GROUPS,
                        Map.of("roles", List.of(MODERATOR, ADMIN))),
                scenario("app-only token with a human group in groups grants nothing", GROUPS,
                        Map.of("groups", List.of(ADMIN))),
                scenario("delegated token with the proxy value in groups grants nothing", GROUPS,
                        delegated("groups", List.of("Blackhole.Proxy"))),
                scenario("delegated token with the proxy value in roles grants nothing", GROUPS,
                        Map.of("scp", "access_as_user", "roles", List.of("Blackhole.Proxy"))),
                scenario("delegated token with the machine metrics value in roles grants nothing", GROUPS,
                        Map.of("scp", "access_as_user", "roles", List.of("Blackhole.Metrics"))),
                scenario("a token without any claim grants nothing", GROUPS, Map.of()),
                scenario("app roles: admin value on a delegated token", APP_ROLES,
                        delegated("roles", List.of("Blackhole.Admin")), "ADMIN", "MODERATOR", "VIEWER"),
                scenario("app roles: moderator value on a delegated token", APP_ROLES,
                        delegated("roles", List.of("Blackhole.Moderator")), "MODERATOR", "VIEWER"),
                scenario("app roles: groups are ignored once the claim is switched", APP_ROLES,
                        delegated("groups", List.of(ADMIN)))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void claimsAreMappedToTheExpectedRoles(RoleMappingProperties mapping, Map<String, Object> claims, Set<String> expected) {
        List<String> roles = new BlackholeRolesFinder(mapping).resolveRoles(claims);

        assertEquals(expected, Set.copyOf(roles), "roles for claims " + claims.keySet());
    }

    static Stream<Arguments> overage() {
        return Stream.of(
                Arguments.of(Named.of("hasgroups on a delegated token", GROUPS), Map.<String, Object>of("scp", "x", "hasgroups", true), true),
                Arguments.of(Named.of("_claim_names names groups", GROUPS), Map.<String, Object>of("scp", "x", "_claim_names", Map.of("groups", "src1")), true),
                Arguments.of(Named.of("groups present", GROUPS), Map.<String, Object>of("scp", "x", "groups", List.of("a"), "hasgroups", true), false),
                Arguments.of(Named.of("app-only token", GROUPS), Map.<String, Object>of("hasgroups", true), false),
                Arguments.of(Named.of("no marker at all", GROUPS), Map.<String, Object>of("scp", "x"), false),
                Arguments.of(Named.of("human claim is roles, not groups", APP_ROLES), Map.<String, Object>of("scp", "x", "hasgroups", true), false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overage")
    void groupsOverageIsRecognisedOnlyForDelegatedTokensWithoutGroups(RoleMappingProperties mapping, Map<String, Object> claims, boolean expected) {
        assertEquals(expected, new BlackholeRolesFinder(mapping).isGroupsOverage(claims), "overage for " + claims);
    }
}
