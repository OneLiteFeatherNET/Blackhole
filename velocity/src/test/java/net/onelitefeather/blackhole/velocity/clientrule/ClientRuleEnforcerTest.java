package net.onelitefeather.blackhole.velocity.clientrule;

import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.client.model.ClientRuleAction;
import net.onelitefeather.blackhole.client.model.ClientRuleKind;
import net.onelitefeather.blackhole.client.model.ClientRuleHitCreateRequest;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.RecordingProxy;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static net.onelitefeather.blackhole.velocity.clientrule.Rules.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRuleEnforcerTest {

    /** A notice channel that delivers to this proxy only, like Redis does to its own subscription. */
    private static final class LoopbackChannel implements ClientRuleNoticeChannel {
        private final List<Consumer<ClientRuleNotice>> listeners = new ArrayList<>();
        final List<ClientRuleNotice> published = new ArrayList<>();

        @Override
        public void onClientRuleNotice(Consumer<ClientRuleNotice> listener) {
            this.listeners.add(listener);
        }

        @Override
        public boolean publish(ClientRuleNotice notice) {
            this.published.add(notice);
            this.listeners.forEach(listener -> listener.accept(notice));
            return true;
        }
    }

    private final TraitCollector traits = new TraitCollector();
    private final FakeClientRuleBackend backend = new FakeClientRuleBackend();
    private final LoopbackChannel channel = new LoopbackChannel();
    private final ClientPlayer staff = new ClientPlayer("Staff", 767, "lobby");
    private final List<ClientRule> rules = new ArrayList<>();
    private final RuleEngine engine = new RuleEngine((rule, server) -> server.startsWith("mini"));
    private ClientRuleEnforcer enforcer;

    private void withRules(ClientRule... active) {
        this.rules.clear();
        this.rules.addAll(List.of(active));
        RuleRegistry registry = new RuleRegistry(new RuleRegistry.Source() {
            @Override
            public Optional<List<ClientRule>> fromCache() {
                return Optional.of(List.copyOf(rules));
            }

            @Override
            public List<ClientRule> fromApi() {
                return List.of();
            }
        });
        registry.reload();
        this.staff.permissions.add(Permissions.CLIENT_RULES_NOTIFY);
        ClientRuleNotifier notifier = new ClientRuleNotifier(() -> List.of(this.staff.player()), this.channel);
        this.enforcer = new ClientRuleEnforcer(this.traits, registry, this.engine, this.backend, notifier, Runnable::run);
    }

    private static String owner(ClientPlayer player) {
        return UUIDConverter.convertToSHA(player.uuid);
    }

    private static String text(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private LoginEvent login(ClientPlayer player) {
        LoginEvent event = new LoginEvent(player.player(), null);
        this.enforcer.onLogin(event);
        return event;
    }

    private ServerPreConnectEvent connect(ClientPlayer player, String server) {
        ServerPreConnectEvent event = new ServerPreConnectEvent(player.player(), RecordingProxy.server(server));
        this.enforcer.onPreConnect(event);
        return event;
    }

    // ---- login -------------------------------------------------------------------------

    @Test
    void aGlobalDenyProtocolRuleRefusesTheLoginWithTheEnglishDefaultAndReportsTheHit() {
        withRules(rule("old", ClientRuleKind.PROTOCOL).range(47, 340).deny().build());
        ClientPlayer old = new ClientPlayer("Old", 47, null);

        LoginEvent event = login(old);

        assertFalse(event.getResult().isAllowed());
        assertEquals(DenyMessages.VERSION, text(event.getResult().getReasonComponent().orElseThrow()));
        assertEquals(1, backend.hits.size());
        ClientRuleHitCreateRequest hit = backend.hits.getFirst();
        assertEquals("old", hit.getRule());
        assertEquals(owner(old), hit.getOwner());
        assertEquals(true, hit.getEnforced());
        assertEquals(false, hit.getBypassed());
    }

    @Test
    void theRulesOwnMessageIsShownExactly() {
        withRules(rule("old", ClientRuleKind.PROTOCOL).range(47, 340).deny().message("Please update to 1.21.").build());

        LoginEvent event = login(new ClientPlayer("Old", 47, null));

        assertEquals("Please update to 1.21.", text(event.getResult().getReasonComponent().orElseThrow()));
    }

    @Test
    void aNotifyRuleLetsThePlayerInButReportsAndAnnouncesTheHit() {
        withRules(rule("old", ClientRuleKind.PROTOCOL).range(47, 340).build());

        LoginEvent event = login(new ClientPlayer("Old", 47, null));

        assertTrue(event.getResult().isAllowed());
        assertEquals(false, backend.hits.getFirst().getEnforced());
        assertEquals(1, staff.messages.size(), "staff are told once");
        assertEquals(List.of(new ClientRuleNotice("Rule old", "Old", "PROTOCOL", "NOTIFY", false)), channel.published);
    }

    @Test
    void staffWithTheBypassPermissionAreNeverRefusedButTheHitIsRecordedAsBypassed() {
        withRules(rule("old", ClientRuleKind.PROTOCOL).range(47, 340).deny().build());
        ClientPlayer admin = new ClientPlayer("Admin", 47, null);
        admin.permissions.add(Permissions.CLIENT_RULES_BYPASS);

        LoginEvent event = login(admin);

        assertTrue(event.getResult().isAllowed());
        assertEquals(true, backend.hits.getFirst().getBypassed());
        assertEquals(false, backend.hits.getFirst().getEnforced());
        assertEquals(false, channel.published.getFirst().enforced());
    }

    @Test
    void aTraitRuleNeverRefusesALoginEvenWhenTheBrandIsAlreadyKnown() {
        withRules(rule("lunar", ClientRuleKind.BRAND).patterns("lunar*").deny().build());
        ClientPlayer lunar = new ClientPlayer("Lunar", 767, null);
        traits.brand(owner(lunar), "lunarclient");

        assertTrue(login(lunar).getResult().isAllowed());
        assertTrue(backend.hits.isEmpty());
    }

    @Test
    void aScopedProtocolRuleIsNotCheckedAtLogin() {
        withRules(rule("old", ClientRuleKind.PROTOCOL).range(47, 340).deny().sections("minigames").build());

        assertTrue(login(new ClientPlayer("Old", 47, null)).getResult().isAllowed());
        assertTrue(backend.hits.isEmpty());
    }

    // ---- connect -----------------------------------------------------------------------

    @Test
    void aDenyRuleRefusesTheConnectAndKeepsAPlayerWhoIsOnAServer() {
        withRules(rule("old", ClientRuleKind.PROTOCOL).range(47, 340).deny().sections("minigames").build());
        ClientPlayer old = new ClientPlayer("Old", 47, "lobby");
        traits.loggedIn(owner(old), 47);

        ServerPreConnectEvent event = connect(old, "minigame-1");

        assertFalse(event.getResult().getServer().isPresent(), "the connect is denied");
        assertEquals(1, old.messages.size());
        assertTrue(old.disconnects.isEmpty(), "the player stays where they are");
        assertEquals("minigame-1", backend.hits.getFirst().getServer());
    }

    @Test
    void withoutACurrentServerTheRefusedPlayerIsDisconnected() {
        withRules(rule("old", ClientRuleKind.PROTOCOL).range(47, 340).deny().sections("minigames").build());
        ClientPlayer old = new ClientPlayer("Old", 47, null);
        traits.loggedIn(owner(old), 47);

        connect(old, "minigame-1");

        assertEquals(1, old.disconnects.size());
        assertEquals(DenyMessages.VERSION, text(old.disconnects.getFirst()));
    }

    @Test
    void aScopedRuleLeavesAConnectToAnotherServerAlone() {
        withRules(rule("old", ClientRuleKind.PROTOCOL).range(47, 340).deny().sections("minigames").build());
        ClientPlayer old = new ClientPlayer("Old", 47, null);
        traits.loggedIn(owner(old), 47);

        ServerPreConnectEvent event = connect(old, "lobby-1");

        assertTrue(event.getResult().isAllowed());
        assertTrue(backend.hits.isEmpty());
    }

    @Test
    void aBrandKnownAtTheFirstConnectIsRefusedThereWithoutWaiting() {
        withRules(rule("lunar", ClientRuleKind.BRAND).patterns("lunar*").deny().build());
        ClientPlayer lunar = new ClientPlayer("Lunar", 767, null);
        traits.loggedIn(owner(lunar), 767);
        traits.brand(owner(lunar), "lunarclient");

        ServerPreConnectEvent event = connect(lunar, "lobby-1");

        assertFalse(event.getResult().isAllowed());
        assertEquals(1, lunar.disconnects.size());
        assertEquals(DenyMessages.CLIENT, text(lunar.disconnects.getFirst()));
    }

    @Test
    void aPlayerWhoseTraitsAreNotKnownYetIsLetThroughAtConnect() {
        withRules(rule("lunar", ClientRuleKind.BRAND).patterns("lunar*").deny().build());
        ClientPlayer unknown = new ClientPlayer("Unknown", 767, null);

        assertTrue(connect(unknown, "lobby-1").getResult().isAllowed());
    }

    // ---- traits arriving late ----------------------------------------------------------

    @Test
    void aDenyBrandThatArrivesLaterDisconnectsThePlayerWithTheRulesMessage() {
        withRules(rule("lunar", ClientRuleKind.BRAND).patterns("lunar*").deny().message("This client is not allowed.").build());
        ClientPlayer lunar = new ClientPlayer("Lunar", 767, "lobby-1");
        traits.loggedIn(owner(lunar), 767);
        enforcer.onTrait(lunar.player());
        assertTrue(lunar.disconnects.isEmpty(), "nothing known yet");

        traits.brand(owner(lunar), "lunarclient");
        enforcer.onTrait(lunar.player());

        assertEquals(List.of("This client is not allowed."), lunar.disconnects.stream().map(ClientRuleEnforcerTest::text).toList());
        assertEquals(true, backend.hits.getFirst().getEnforced());
    }

    @Test
    void aChannelThatArrivesLaterIsCheckedAgainstRulesOfTheServerThePlayerIsOn() {
        withRules(rule("meteor", ClientRuleKind.CHANNEL).patterns("meteor-client:*").deny().sections("minigames").build());
        ClientPlayer onLobby = new ClientPlayer("Lobby", 767, "lobby-1");
        ClientPlayer onMinigame = new ClientPlayer("Mini", 767, "minigame-1");
        for (ClientPlayer player : List.of(onLobby, onMinigame)) {
            traits.channels(owner(player), List.of("meteor-client:main"));
            enforcer.onTrait(player.player());
        }

        assertTrue(onLobby.disconnects.isEmpty(), "the rule does not cover the lobby");
        assertEquals(1, onMinigame.disconnects.size());
    }

    @Test
    void aTraitNobodyChecksForLeavesThePlayerAlone() {
        withRules(rule("meteor", ClientRuleKind.CHANNEL).patterns("meteor-client:*").deny().build());
        ClientPlayer fabric = new ClientPlayer("Fabric", 767, "lobby-1");
        traits.channels(owner(fabric), List.of("fabric:registry/sync"));

        enforcer.onTrait(fabric.player());

        assertTrue(fabric.disconnects.isEmpty());
        assertTrue(backend.hits.isEmpty());
    }

    // ---- other actions, dedupe, fail open ----------------------------------------------

    @Test
    void aTemplateRuleIsReportedButNeverRefusedByTheProxy() {
        withRules(rule("brand", ClientRuleKind.BRAND).patterns("lunar*").action(ClientRuleAction.TEMPLATE).build());
        ClientPlayer lunar = new ClientPlayer("Lunar", 767, "lobby-1");
        traits.brand(owner(lunar), "lunarclient");

        enforcer.onTrait(lunar.player());

        assertTrue(lunar.disconnects.isEmpty());
        assertEquals(1, backend.hits.size());
        assertEquals("TEMPLATE", channel.published.getFirst().action());
    }

    @Test
    void aHitIsReportedAndAnnouncedOncePerPlayerRuleAndSessionButARefusalRepeats() {
        withRules(rule("old", ClientRuleKind.PROTOCOL).range(47, 340).deny().sections("minigames").build());
        ClientPlayer old = new ClientPlayer("Old", 47, "lobby");
        traits.loggedIn(owner(old), 47);

        connect(old, "minigame-1");
        ServerPreConnectEvent second = connect(old, "minigame-2");

        assertFalse(second.getResult().isAllowed(), "still refused");
        assertEquals(2, old.messages.size(), "and told again");
        assertEquals(1, backend.hits.size(), "but reported once");
        assertEquals(1, channel.published.size(), "and announced once");
    }

    @Test
    void aNewSessionReportsTheSameRuleAgain() {
        withRules(rule("old", ClientRuleKind.PROTOCOL).range(47, 340).build());
        ClientPlayer old = new ClientPlayer("Old", 47, null);

        login(old);
        enforcer.onDisconnect(owner(old));
        login(old);

        assertEquals(2, backend.hits.size());
    }

    @Test
    void withNoRulesLoadedNothingIsEnforced() {
        withRules();

        assertTrue(login(new ClientPlayer("Old", 47, null)).getResult().isAllowed());
        assertTrue(backend.hits.isEmpty());
    }

    @Test
    void whenTheBackendIsDownTheRulesStillRefuseAndStaffAreStillTold() {
        withRules(rule("old", ClientRuleKind.PROTOCOL).range(47, 340).deny().build());
        backend.hitFailure = new ApiException(503, "down");

        LoginEvent event = login(new ClientPlayer("Old", 47, null));

        assertFalse(event.getResult().isAllowed());
        assertTrue(backend.hits.isEmpty(), "nothing recorded");
        assertEquals(1, staff.messages.size(), "the notice does not depend on the backend");
    }

    @Test
    void anErrorWhileCheckingNeverBlocksThePlayer() {
        withRules(rule("old", ClientRuleKind.PROTOCOL).range(47, 340).deny().sections("minigames").build());
        RuleEngine broken = new RuleEngine((rule, server) -> {
            throw new IllegalStateException("cloudnet exploded");
        });
        RuleRegistry registry = new RuleRegistry(new RuleRegistry.Source() {
            @Override
            public Optional<List<ClientRule>> fromCache() {
                return Optional.of(rules);
            }

            @Override
            public List<ClientRule> fromApi() {
                return List.of();
            }
        });
        registry.reload();
        ClientRuleEnforcer fragile = new ClientRuleEnforcer(traits, registry, broken, backend,
                new ClientRuleNotifier(() -> List.of(), channel), Runnable::run);
        ClientPlayer old = new ClientPlayer("Old", 47, "lobby");
        traits.loggedIn(owner(old), 47);
        ServerPreConnectEvent event = new ServerPreConnectEvent(old.player(), RecordingProxy.server("minigame-1"));

        fragile.onPreConnect(event);

        assertTrue(event.getResult().isAllowed());
    }

    @Test
    void theStaffNoticeCarriesNoTraitAndTheHitRequestCarriesNoTraitEither() {
        withRules(rule("lunar", ClientRuleKind.BRAND).patterns("secretbrand*").build());
        ClientPlayer lunar = new ClientPlayer("Lunar", 767, "lobby-1");
        traits.brand(owner(lunar), "secretbrand-1.2");

        enforcer.onTrait(lunar.player());

        assertFalse(channel.published.getFirst().toString().contains("secretbrand-1.2"));
        assertFalse(backend.hits.getFirst().toString().contains("secretbrand-1.2"));
    }
}
