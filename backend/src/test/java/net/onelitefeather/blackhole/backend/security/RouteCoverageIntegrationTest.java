package net.onelitefeather.blackhole.backend.security;

import io.micronaut.security.annotation.Secured;
import io.micronaut.web.router.Router;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks every route the application registers and compares its roles with the permission matrix of
 * the design (add-team-sso, decision 3). A new endpoint without a rule, or with other roles than the
 * matrix says, fails the build.
 */
@Tag("integration")
class RouteCoverageIntegrationTest extends ApiIntegrationTest {

    private static final Set<String> V = Set.of("VIEWER");
    private static final Set<String> P = Set.of("PROXY");
    private static final Set<String> VP =Set.of("VIEWER", "PROXY");
    private static final Set<String> M = Set.of("MODERATOR");
    private static final Set<String> MP = Set.of("MODERATOR", "PROXY");
    private static final Set<String> A = Set.of("ADMIN");
    private static final Set<String> D = Set.of("DISCORD");
    /** A moderator, an admin, or the Discord bot naming the staff member it acts for (the delegation routes). */
    private static final Set<String> MAD = Set.of("MODERATOR", "ADMIN", "DISCORD");
    private static final Set<String> MD = Set.of("MODERATOR", "DISCORD");
    /** A moderator, or the proxy or the Discord bot naming the staff member it acts for (the guard and area ban routes). */
    private static final Set<String> MPD = Set.of("MODERATOR", "PROXY", "DISCORD");
    /**
     * The routes on which the Discord bot may act for a linked staff member (StaffDelegation): nowhere else. The guard level
     * and the area ban routes are also delegation routes, so the proxy and the bot may act for staff there.
     */
    private static final Set<String> DELEGATION_ROUTES = Set.of("POST /appeal/{identifier}/review", "PATCH /case/{reference}",
            "PUT /case/{reference}/ticket/freeze", "POST /discord/tickets/{id}/close", "POST /guard/level", "POST /area-bans",
            "POST /area-bans/{id}/lift");
    private static final Set<String> AUTHENTICATED = Set.of("isAuthenticated()");
    private static final Set<String> ANONYMOUS = Set.of("isAnonymous()");
    /** A valid player session (PlayerSessionRule.ROLE): neither a staff role nor the proxy. */
    private static final Set<String> PLAYER = Set.of("PLAYER_SESSION");
    /** The ban-list routes: Micronaut lets them through, and BanListAccessFilter demands a named client's Basic credential. */
    private static final Set<String> BASIC_CLIENT = Set.of("isAnonymous()");

    /** The matrix: route -> roles that may call it (any one of them). */
    private static final Map<String, Set<String>> MATRIX = Map.ofEntries(
            // read-only
            Map.entry("GET /punishment", VP),
            Map.entry("GET /punishment/short/{shortId}", VP),
            Map.entry("GET /punishment/active", V),
            Map.entry("GET /profile", VP),
            Map.entry("GET /profile/{owner}", VP),
            Map.entry("GET /elo/{owner}", VP),
            Map.entry("GET /elo/{owner}/history", V),
            Map.entry("GET /report", V),
            Map.entry("GET /report/{identifier}", VP),
            Map.entry("GET /appeal", V),
            Map.entry("GET /section", VP),
            Map.entry("GET /section/{key}", VP),
            Map.entry("GET /template", VP),
            Map.entry("GET /template/{identifier}", VP),
            Map.entry("GET /dashboard/summary", V),
            Map.entry("GET /inbox", V),
            Map.entry("GET /player/resolve/{name}", VP),
            Map.entry("GET /punishment/{reference}/evidence", VP),
            Map.entry("GET /punishment/{reference}/evidence/count", VP),
            Map.entry("GET /evidence", VP),
            Map.entry("GET /evidence/{identifier}", V),
            Map.entry("GET /evidence/{identifier}/content", V),
            Map.entry("GET /cloudnet/catalogue", V),
            Map.entry("GET /proxy/capabilities", V),
            Map.entry("GET /client-rule", VP),
            Map.entry("GET /client-rule/{key}", VP),
            Map.entry("GET /client-rule/catalogue", V),
            Map.entry("GET /client-rule/hits", V),
            Map.entry("GET /client-rule/{key}/hits", V),
            Map.entry("GET /case", V),
            Map.entry("GET /case/{reference}", V),
            Map.entry("GET /case/{reference}/timeline", V),
            Map.entry("GET /case/{reference}/protocol", V),
            Map.entry("GET /case/{reference}/protocol/{evidenceId}", V),
            Map.entry("GET /case/{reference}/protocol/{evidenceId}/download", V),
            Map.entry("GET /evasion/{owner}/sightings", V),
            // kicks: a moderator starts one (the proxy is denied), an admin starts a mass kick, the proxy reports deliveries
            Map.entry("POST /kick", M),
            Map.entry("POST /kick/mass", A),
            Map.entry("GET /kick/{id}", VP),
            Map.entry("POST /kick/{id}/deliveries", P),
            // staff rollback: an admin previews, starts, follows and cancels a rollback of one staff member's punishments
            Map.entry("POST /admin/rollback/preview", A),
            Map.entry("POST /admin/rollback/{id}/execute", A),
            Map.entry("GET /admin/rollback/{id}", A),
            Map.entry("POST /admin/rollback/{id}/cancel", A),
            // exemption: staff and the proxy read a player's status; only the proxy reports permissions; admins decide reviews
            Map.entry("GET /exemption/{owner}", VP),
            Map.entry("POST /exemption/report", P),
            Map.entry("GET /exemption/reviews", A),
            Map.entry("POST /exemption/reviews/{id}/apply", A),
            Map.entry("POST /exemption/reviews/{id}/dismiss", A),
            // warnings: the player's proxy reads and confirms them; viewers read one warning
            Map.entry("GET /warn/{id}", VP),
            Map.entry("GET /warn/pending", P),
            Map.entry("POST /warn/{id}/acknowledge", P),
            // moderation
            Map.entry("PATCH /case/{reference}", MD),
            Map.entry("PUT /case/{reference}/ticket/freeze", MAD),
            Map.entry("POST /case/{reference}/notes", M),
            Map.entry("PUT /case/{reference}/notes/{noteId}", M),
            Map.entry("POST /case/{reference}/messages", M),
            // chat files and evidence sharing: sharing, revoking and the upload need a moderator; reading and the preview a viewer
            Map.entry("POST /case/{reference}/attachments", M),
            Map.entry("GET /case/{reference}/evidence-shares", V),
            Map.entry("POST /case/{reference}/evidence-shares/preview", V),
            Map.entry("POST /case/{reference}/evidence-shares", M),
            Map.entry("POST /case/{reference}/evidence-shares/{shareId}/revoke", M),
            Map.entry("PUT /case/{reference}/messages/{messageId}", M),
            Map.entry("DELETE /case/{reference}/messages/{messageId}", M),
            Map.entry("POST /case/{reference}/ban-id/leak", M),
            Map.entry("GET /punishment/{reference}/ban-id", MP),
            Map.entry("POST /punishment/lookup", MP),
            Map.entry("POST /punishment/apply", MP),
            Map.entry("POST /punishment/preview", M),
            Map.entry("POST /punishment/revoke", MP),
            Map.entry("POST /report/{identifier}/resolve", M),
            Map.entry("POST /appeal/{identifier}/review", MAD),
            Map.entry("POST /elo/{owner}/adjust", M),
            Map.entry("POST /evidence", MP),
            Map.entry("POST /evidence/file", MP),
            Map.entry("POST /profile", M),
            Map.entry("POST /profile/update/{owner}", M),
            // intake (the proxy's routes)
            Map.entry("POST /report", MP),
            Map.entry("POST /appeal", MP),
            Map.entry("POST /elo/chat", MP),
            Map.entry("POST /evasion/record", MP),
            Map.entry("POST /evasion/fingerprint", P),
            // player sessions: the proxy reports and updates them, staff read them (the proxy may not)
            Map.entry("POST /intel/sessions", P),
            Map.entry("PATCH /intel/sessions/{sessionId}", P),
            Map.entry("GET /intel/players/{owner}/sessions", V),
            // name history: staff read it, and the proxy reads it for the /blackhole <user> names command
            Map.entry("GET /intel/players/{owner}/names", VP),
            Map.entry("GET /intel/names/{name}/owners", VP),
            // VPN and hosting detection: the proxy asks for a verdict; staff read and set the policy and exemptions
            Map.entry("POST /intel/login-check", P),
            Map.entry("GET /intel/policy", V),
            Map.entry("POST /intel/policy", M),
            Map.entry("POST /intel/allow-list", M),
            Map.entry("GET /intel/allow-list", V),
            Map.entry("DELETE /intel/allow-list/{owner}", M),
            Map.entry("GET /intel/lists", V),
            Map.entry("GET /intel/players/{owner}/hits", V),
            // join guard: staff read and set the level, the proxy reports its staff presence; area bans are staff actions
            Map.entry("GET /guard/state", V),
            Map.entry("POST /guard/level", MPD),
            Map.entry("POST /guard/presence", P),
            // join guard settings: staff read the stored unattended settings, only an administrator replaces them
            Map.entry("GET /guard/settings", V),
            Map.entry("PUT /guard/settings", A),
            Map.entry("GET /area-bans", V),
            Map.entry("POST /area-bans", MPD),
            Map.entry("POST /area-bans/{id}/lift", MPD),
            Map.entry("POST /profile/{owner}/session", MP),
            // IP bans: staff create and lift (a moderator within the duration limit, an admin permanently), viewers read,
            // the proxy reads its active list
            Map.entry("POST /ipban", M),
            Map.entry("GET /ipban", V),
            Map.entry("GET /ipban/active", P),
            Map.entry("GET /ipban/{id}", V),
            Map.entry("POST /ipban/{id}/lift", M),
            // admin configuration
            Map.entry("POST /section", A),
            Map.entry("POST /section/update", A),
            Map.entry("POST /section/{key}/disable", A),
            Map.entry("POST /client-rule", A),
            Map.entry("PUT /client-rule/{key}", A),
            Map.entry("DELETE /client-rule/{key}", A),
            Map.entry("POST /template", A),
            Map.entry("POST /template/update", A),
            Map.entry("DELETE /template/delete/{identifier}", A),
            Map.entry("POST /admin/import/vanilla", A),
            Map.entry("GET /admin/import/punishments", A),
            Map.entry("POST /admin/import/punishments/retemplate", A),
            Map.entry("GET /admin/staff-actor", A),
            Map.entry("POST /admin/staff-actor/{oid}/anonymize", A),
            // the staff audit log is read by administrators only
            Map.entry("GET /admin/audit", A),
            // erasure and destructive
            Map.entry("POST /case/{reference}/notes/{noteId}/redact", A),
            Map.entry("POST /case/{reference}/messages/{messageId}/redact", A),
            Map.entry("POST /case/{reference}/transcript/erase-player", A),
            Map.entry("POST /case/{reference}/ban-id/reissue", A),
            Map.entry("GET /case/{reference}/transcript", A),
            Map.entry("DELETE /evidence/{identifier}", A),
            Map.entry("DELETE /evidence/owner/{owner}", A),
            Map.entry("DELETE /profile/delete/{owner}", A),
            // staff account link: own (any staff role), the proxy's confirmation and lookup, admin
            Map.entry("POST /staff-link/code", V),
            Map.entry("GET /staff-link/me", V),
            Map.entry("DELETE /staff-link/me", V),
            Map.entry("PUT /cloudnet/catalogue", P),
            Map.entry("PUT /client-rule/catalogue", P),
            Map.entry("PUT /proxy/capabilities", P),
            Map.entry("POST /client-rule/hit", P),
            Map.entry("GET /report/feedback/{owner}", P),
            Map.entry("POST /report/feedback/{identifier}/ack", P),
            Map.entry("POST /staff-link/confirm", P),
            Map.entry("GET /staff-link/by-minecraft/{uuid}", P),
            Map.entry("GET /admin/staff-link", A),
            Map.entry("DELETE /admin/staff-link/{oid}", A),
            // staff display name: own profile, any staff role (a service principal is refused by the service)
            Map.entry("GET /staff-profile/me", V),
            Map.entry("PUT /staff-profile/me", V),
            // discord account link: own (any staff role), the proxy's player code, the bot's facade, admin
            Map.entry("POST /staff-discord-link/code", V),
            Map.entry("GET /staff-discord-link/me", V),
            Map.entry("DELETE /staff-discord-link/me", V),
            Map.entry("POST /player-discord-link/code", P),
            Map.entry("POST /discord/link/confirm", D),
            Map.entry("GET /discord/link/{discordUserId}", D),
            Map.entry("DELETE /discord/link/{discordUserId}", D),
            Map.entry("GET /discord/notifications/report/{identifier}", D),
            Map.entry("GET /discord/notifications/appeal/{identifier}", D),
            // discord tickets: the bot only (staff and the proxy are denied); erasing a participant is an admin act
            Map.entry("POST /discord/tickets", D),
            Map.entry("GET /discord/tickets", D),
            Map.entry("GET /discord/tickets/{id}", D),
            Map.entry("PATCH /discord/tickets/{id}", D),
            Map.entry("GET /discord/tickets/{id}/outbox", D),
            Map.entry("POST /discord/tickets/{id}/messages", D),
            Map.entry("POST /discord/tickets/{id}/links", D),
            Map.entry("POST /discord/tickets/{id}/close", D),
            Map.entry("POST /discord/tickets/{id}/protocol/ensure", D),
            Map.entry("GET /discord/tickets/{id}/attachments/{attachmentId}", D),
            Map.entry("POST /discord/tickets/{id}/attachments", D),
            Map.entry("GET /discord/staff-links", D),
            Map.entry("GET /discord/staff-links/{discordUserId}", D),
            Map.entry("POST /discord/leak-check", D),
            Map.entry("POST /case/{reference}/participants/{participantId}/erase", A),
            Map.entry("GET /admin/discord-link", A),
            Map.entry("DELETE /admin/discord-link/{id}", A),
            // identity
            Map.entry("GET /auth/me", AUTHENTICATED),
            // the player portal: the login is anonymous, everything else needs a player session
            Map.entry("POST /public/session", ANONYMOUS),
            Map.entry("GET /public/session", PLAYER),
            Map.entry("DELETE /public/session", PLAYER),
            Map.entry("GET /public/punishment", PLAYER),
            Map.entry("POST /public/appeal", PLAYER),
            Map.entry("GET /public/appeal", PLAYER),
            Map.entry("GET /public/conversation", PLAYER),
            Map.entry("GET /public/appeal/protocol", PLAYER),
            Map.entry("GET /public/appeal/conditions", PLAYER),
            Map.entry("GET /appeal-conditions", V),
            Map.entry("GET /appeal-conditions/versions", V),
            Map.entry("POST /appeal-conditions", A),
            Map.entry("POST /public/messages", PLAYER),
            // chat files and the live signal: a player's own staged file, download and removal, and the conversation's stream
            Map.entry("POST /public/attachments", PLAYER),
            Map.entry("GET /public/attachments/{id}", PLAYER),
            Map.entry("DELETE /public/attachments/{id}", PLAYER),
            Map.entry("GET /public/conversation/events", PLAYER),
            // a staff head, by its keyed avatar key: served to the portal's player session only
            Map.entry("GET /public/staff-avatar/{avatarKey}", PLAYER),
            // the public ban list for partner networks: HTTP Basic with a named client (BanListAccessFilter), no Entra token
            Map.entry("GET /public/bans", BASIC_CLIENT),
            Map.entry("GET /public/bans/summary", BASIC_CLIENT),
            Map.entry("GET /public/bans/{hash}", BASIC_CLIENT),
            Map.entry("GET /public/bans/{hash}/{rest:.+}", BASIC_CLIENT),
            // the firewall pull feeds: anonymous at the role layer, the token and the source allow-list are checked by FeedService
            Map.entry("GET /firewall/feed/{token}/{feed}", ANONYMOUS),
            // the case's live signal: viewers and above (the proxy and the Discord bot are denied)
            Map.entry("GET /case/{reference}/events", V)
    );

    /** Routes without @Secured, protected by the intercept-url-map or public (see application.yml). */
    private static final Set<String> CONFIGURED_IN_YAML = Set.of("GET /health", "GET /health/{selector}", "GET /prometheus");

    @Test
    void everyRouteHasARuleAndItsRolesEqualTheMatrix() {
        Map<String, Set<String>> actual = new TreeMap<>();
        Set<String> withoutRule = new TreeSet<>();
        this.context.getBean(Router.class).uriRoutes().filter(route -> !"HEAD".equals(route.getHttpMethodName())).forEach(route -> {
            String key = route.getHttpMethodName() + " " + route.getUriMatchTemplate();
            Set<String> roles = Set.of(route.getAnnotationMetadata().stringValues(Secured.class));
            if (roles.isEmpty()) {
                withoutRule.add(key);
            } else {
                actual.put(key, roles);
            }
        });

        assertEquals(new TreeMap<>(MATRIX), actual, "routes with an @Secured rule must equal the matrix");
        Set<String> unexpected = withoutRule.stream().filter(route -> !CONFIGURED_IN_YAML.contains(route)).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of(), unexpected, "routes without any rule (they would be rejected)");
    }

    @Test
    void onlyTheLoginIsAnonymousUnderThePublicPrefixAndEveryOtherRouteNeedsAPlayerSessionAndNoStaffRole() {
        Map<String, Set<String>> publicRoutes = new TreeMap<>();
        this.context.getBean(Router.class).uriRoutes().filter(route -> !"HEAD".equals(route.getHttpMethodName()))
                .filter(route -> route.getUriMatchTemplate().toString().startsWith("/public"))
                .filter(route -> !route.getUriMatchTemplate().toString().startsWith("/public/bans"))
                .forEach(route -> publicRoutes.put(route.getHttpMethodName() + " " + route.getUriMatchTemplate(),
                        Set.of(route.getAnnotationMetadata().stringValues(Secured.class))));

        assertTrue(!publicRoutes.isEmpty(), "the portal has routes");
        publicRoutes.forEach((route, roles) -> {
            if (route.equals("POST /public/session")) {
                assertEquals(ANONYMOUS, roles, route);
            } else {
                assertEquals(PLAYER, roles, route + " needs a player session and no staff role");
            }
        });
    }

    @Test
    void noRouteOutsideThePublicPrefixAcceptsAPlayerSession() {
        this.context.getBean(Router.class).uriRoutes().filter(route -> !"HEAD".equals(route.getHttpMethodName()))
                .filter(route -> !route.getUriMatchTemplate().toString().startsWith("/public"))
                .forEach(route -> assertTrue(!Set.of(route.getAnnotationMetadata().stringValues(Secured.class)).contains("PLAYER_SESSION"),
                        route.getUriMatchTemplate() + " must not accept a player session"));
    }

    @Test
    void everyDiscordRouteIsForTheBotAloneAndNoOtherRouteAllowsTheBot() {
        Map<String, Set<String>> discordRoutes = new TreeMap<>();
        Set<String> botElsewhere = new TreeSet<>();
        this.context.getBean(Router.class).uriRoutes().filter(route -> !"HEAD".equals(route.getHttpMethodName())).forEach(route -> {
            String key = route.getHttpMethodName() + " " + route.getUriMatchTemplate();
            Set<String> roles = Set.of(route.getAnnotationMetadata().stringValues(Secured.class));
            if (route.getUriMatchTemplate().toString().startsWith("/discord/")) {
                discordRoutes.put(key, roles);
            } else if (roles.contains("DISCORD") && !DELEGATION_ROUTES.contains(key)) {
                botElsewhere.add(key);
            }
        });

        assertTrue(discordRoutes.size() >= 16, "the whole /discord/** facade is registered: " + discordRoutes.keySet());
        discordRoutes.forEach((route, roles) -> assertEquals(D, roles, route + " allows the bot and nobody else"));
        assertEquals(Set.of(), botElsewhere, "the bot is allowed nowhere but /discord/** and the delegation routes");
    }
}
