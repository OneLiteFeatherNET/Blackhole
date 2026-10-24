package net.onelitefeather.blackhole.velocity.clientrule;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent.SignedState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.client.model.ClientRuleAction;
import net.onelitefeather.blackhole.client.model.ClientRuleKind;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static net.onelitefeather.blackhole.velocity.clientrule.Rules.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Velocity 4.3.0 facts behind these tests (task 5.1, read from the API sources): {@code CommandExecuteEvent} is
 * awaited and fires before the proxy handles the command or forwards it to the backend server;
 * {@code getInvocationInfo().signedState()} exposes the signed state and {@code source()} tells a typed command
 * from an API call; {@code CommandResult.denied()} stops both handling and forwarding; the javadoc of
 * {@code SIGNED_WITH_ARGS} says cancelling it kicks the player. {@code PlayerAvailableCommandsEvent} is awaited and
 * carries a mutable root node; the velocity-brigadier fork offers {@code removeChildByName}.
 */
class CommandRuleEnforcerTest {

    private final FakeClientRuleBackend backend = new FakeClientRuleBackend();
    private final MutableClock clock = new MutableClock();
    private final List<ClientRule> rules = new ArrayList<>();
    private final List<ClientRuleNotice> notices = new ArrayList<>();
    private final RuleEngine engine = new RuleEngine((rule, server) -> server.startsWith("mini"));
    private RuleRegistry registry;
    private CommandRuleEnforcer withRelay;
    private CommandRuleEnforcer withoutRelay;

    private void withRules(ClientRule... active) {
        this.rules.clear();
        this.rules.addAll(List.of(active));
        this.registry = new RuleRegistry(new RuleRegistry.Source() {
            @Override
            public Optional<List<ClientRule>> fromCache() {
                return Optional.of(List.copyOf(rules));
            }

            @Override
            public List<ClientRule> fromApi() {
                return List.of();
            }
        });
        this.registry.reload();
        ClientRuleNoticeChannel channel = new ClientRuleNoticeChannel() {
            @Override
            public void onClientRuleNotice(Consumer<ClientRuleNotice> listener) {
            }

            @Override
            public boolean publish(ClientRuleNotice notice) {
                notices.add(notice);
                return true;
            }
        };
        HitReporter reporter = new HitReporter(this.backend, new ClientRuleNotifier(List::of, channel), Runnable::run);
        this.withRelay = new CommandRuleEnforcer(this.registry, this.engine, reporter, new CommandDenial(true), this.clock);
        this.withoutRelay = new CommandRuleEnforcer(this.registry, this.engine, reporter, new CommandDenial(false), this.clock);
    }

    private static CommandExecuteEvent command(ClientPlayer player, String line, SignedState state) {
        return new CommandExecuteEvent(player.player(), line, new CommandExecuteEvent.InvocationInfo(state, CommandExecuteEvent.Source.PLAYER));
    }

    private static String text(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private ClientRule denyOp() {
        return rule("no-op", ClientRuleKind.COMMAND).patterns("op").deny().build();
    }

    // ---- matching ----------------------------------------------------------------------

    @Test
    void aForwardedCommandIsDeniedAndThePlayerSeesTheRulesMessage() {
        withRules(rule("no-op", ClientRuleKind.COMMAND).patterns("op").deny().message("You may not do that.").build());
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");
        CommandExecuteEvent event = command(player, "op me", SignedState.UNSIGNED);

        withRelay.onCommand(player.player(), event);

        assertFalse(event.getResult().isAllowed(), "neither executed on the proxy nor forwarded");
        assertEquals(List.of("You may not do that."), player.messages.stream().map(CommandRuleEnforcerTest::text).toList());
        assertEquals(true, backend.hits.getFirst().getEnforced());
    }

    @Test
    void theNamespacedFormAndLeadingSpacesAndCaseAreCaught() {
        withRules(denyOp());
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");

        for (String line : new String[]{"minecraft:op me", "  op me", "/  OP me", "bukkit:OP"}) {
            CommandExecuteEvent event = command(player, line, SignedState.UNSIGNED);
            withRelay.onCommand(player.player(), event);
            assertFalse(event.getResult().isAllowed(), "'" + line + "' must be denied");
        }
    }

    @Test
    void argumentsAreNotMatchedAndOtherCommandsRunNormally() {
        withRules(denyOp());
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");
        CommandExecuteEvent event = command(player, "msg Alex op", SignedState.UNSIGNED);

        withRelay.onCommand(player.player(), event);

        assertTrue(event.getResult().isAllowed());
        assertTrue(backend.hits.isEmpty());
    }

    // ---- scope -------------------------------------------------------------------------

    @Test
    void aScopedRuleAppliesOnTheCoveredServerAndNotOnAnother() {
        withRules(rule("no-op", ClientRuleKind.COMMAND).patterns("op").deny().sections("minigames").build());
        ClientPlayer onMini = new ClientPlayer("Mini", 767, "minigame-1");
        ClientPlayer onLobby = new ClientPlayer("Lobby", 767, "lobby-1");
        CommandExecuteEvent miniEvent = command(onMini, "op me", SignedState.UNSIGNED);
        CommandExecuteEvent lobbyEvent = command(onLobby, "op me", SignedState.UNSIGNED);

        withRelay.onCommand(onMini.player(), miniEvent);
        withRelay.onCommand(onLobby.player(), lobbyEvent);

        assertFalse(miniEvent.getResult().isAllowed());
        assertTrue(lobbyEvent.getResult().isAllowed(), "the command runs normally outside the section");
    }

    @Test
    void withoutACurrentServerOnlyGlobalRulesApply() {
        withRules(rule("scoped", ClientRuleKind.COMMAND).patterns("op").deny().sections("minigames").build(),
                rule("global", ClientRuleKind.COMMAND).patterns("op").deny().build());
        ClientPlayer player = new ClientPlayer("Steve", 767, null);
        CommandExecuteEvent event = command(player, "op me", SignedState.UNSIGNED);

        withRelay.onCommand(player.player(), event);

        assertFalse(event.getResult().isAllowed());
        assertEquals(List.of("global"), backend.hits.stream().map(hit -> hit.getRule()).toList());
    }

    // ---- signed commands ---------------------------------------------------------------

    @Test
    void aSignedCommandWithArgumentsIsDeniedWithSignedVelocity() {
        withRules(denyOp());
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");
        CommandExecuteEvent event = command(player, "op me", SignedState.SIGNED_WITH_ARGS);

        withRelay.onCommand(player.player(), event);

        assertFalse(event.getResult().isAllowed());
        assertEquals(true, backend.hits.getFirst().getEnforced());
    }

    @Test
    void aSignedCommandWithArgumentsIsDeliveredWithoutSignedVelocityButThePlayerIsToldAndTheHitIsNotEnforced() {
        withRules(denyOp());
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");
        CommandExecuteEvent event = command(player, "op me", SignedState.SIGNED_WITH_ARGS);

        withoutRelay.onCommand(player.player(), event);

        assertTrue(event.getResult().isAllowed(), "cancelling it would make Velocity kick the player");
        assertEquals(1, player.messages.size(), "the player is told it is not allowed");
        assertEquals(false, backend.hits.getFirst().getEnforced());
        assertEquals(false, notices.getFirst().enforced());
    }

    @Test
    void anUnsignedCommandIsDeniedEvenWithoutSignedVelocity() {
        withRules(denyOp());
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");
        CommandExecuteEvent event = command(player, "op me", SignedState.UNSIGNED);

        withoutRelay.onCommand(player.player(), event);

        assertFalse(event.getResult().isAllowed());
    }

    // ---- bypass, actions, dedupe, fail open --------------------------------------------

    @Test
    void staffWithTheBypassPermissionRunTheCommandAndTheHitIsRecordedAsBypassed() {
        withRules(denyOp());
        ClientPlayer admin = new ClientPlayer("Admin", 767, "lobby-1");
        admin.permissions.add(Permissions.CLIENT_RULES_BYPASS);
        CommandExecuteEvent event = command(admin, "op me", SignedState.UNSIGNED);

        withRelay.onCommand(admin.player(), event);

        assertTrue(event.getResult().isAllowed());
        assertTrue(admin.messages.isEmpty());
        assertEquals(true, backend.hits.getFirst().getBypassed());
        assertEquals(false, backend.hits.getFirst().getEnforced());
    }

    @Test
    void aNotifyOrTemplateRuleReportsButNeverBlocksOrTellsThePlayer() {
        withRules(rule("watch", ClientRuleKind.COMMAND).patterns("gamemode").build(),
                rule("punish", ClientRuleKind.COMMAND).patterns("gamemode").action(ClientRuleAction.TEMPLATE).build());
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");
        CommandExecuteEvent event = command(player, "gamemode 1", SignedState.UNSIGNED);

        withRelay.onCommand(player.player(), event);

        assertTrue(event.getResult().isAllowed());
        assertTrue(player.messages.isEmpty());
        assertEquals(2, backend.hits.size());
    }

    @Test
    void aHeldKeyReportsOncePerMinutePerPlayerAndRuleButTheDenialRepeats() {
        withRules(denyOp());
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");

        for (int i = 0; i < 5; i++) {
            CommandExecuteEvent event = command(player, "op me", SignedState.UNSIGNED);
            withRelay.onCommand(player.player(), event);
            assertFalse(event.getResult().isAllowed());
        }
        assertEquals(1, backend.hits.size());
        assertEquals(1, notices.size());

        clock.advance(Duration.ofSeconds(59));
        withRelay.onCommand(player.player(), command(player, "op me", SignedState.UNSIGNED));
        assertEquals(1, backend.hits.size(), "59 seconds later: still deduplicated");

        clock.advance(Duration.ofSeconds(1));
        withRelay.onCommand(player.player(), command(player, "op me", SignedState.UNSIGNED));
        assertEquals(2, backend.hits.size(), "a minute after the first report");
    }

    @Test
    void aCommandRunByAPluginThroughTheApiIsNotChecked() {
        withRules(denyOp());
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");
        CommandExecuteEvent event = new CommandExecuteEvent(player.player(), "op me",
                new CommandExecuteEvent.InvocationInfo(SignedState.UNSUPPORTED, CommandExecuteEvent.Source.API));

        withRelay.onCommand(player.player(), event);

        assertTrue(event.getResult().isAllowed());
        assertTrue(backend.hits.isEmpty());
    }

    @Test
    void withNoRulesLoadedOrABackendDownNothingBreaks() {
        withRules();
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");
        CommandExecuteEvent none = command(player, "op me", SignedState.UNSIGNED);
        withRelay.onCommand(player.player(), none);
        assertTrue(none.getResult().isAllowed());

        withRules(denyOp());
        backend.hitFailure = new net.onelitefeather.blackhole.client.invoker.ApiException(503, "down");
        CommandExecuteEvent denied = command(player, "op me", SignedState.UNSIGNED);
        withRelay.onCommand(player.player(), denied);
        assertFalse(denied.getResult().isAllowed(), "the proxy decides without the backend");
    }

    @Test
    void anErrorWhileCheckingNeverBlocksTheCommand() {
        withRules(rule("scoped", ClientRuleKind.COMMAND).patterns("op").deny().sections("minigames").build());
        RuleEngine broken = new RuleEngine((rule, server) -> {
            throw new IllegalStateException("cloudnet exploded");
        });
        CommandRuleEnforcer fragile = new CommandRuleEnforcer(registry, broken,
                new HitReporter(backend, new ClientRuleNotifier(List::of, new ClientRuleNoticeChannel() {
                    @Override
                    public void onClientRuleNotice(Consumer<ClientRuleNotice> listener) {
                    }

                    @Override
                    public boolean publish(ClientRuleNotice notice) {
                        return false;
                    }
                }), Runnable::run), new CommandDenial(true), clock);
        ClientPlayer player = new ClientPlayer("Steve", 767, "minigame-1");
        CommandExecuteEvent event = command(player, "op me", SignedState.UNSIGNED);

        fragile.onCommand(player.player(), event);

        assertTrue(event.getResult().isAllowed());
    }

    // ---- hiding ------------------------------------------------------------------------

    private static RootCommandNode<Object> tree(String... names) {
        RootCommandNode<Object> root = new RootCommandNode<>();
        for (String name : names) {
            root.addChild(LiteralArgumentBuilder.<Object>literal(name).build());
        }
        return root;
    }

    private static List<String> names(RootCommandNode<?> root) {
        return root.getChildren().stream().map(node -> node.getName()).sorted().toList();
    }

    @Test
    void hidingRemovesOnlyTheCoveredCommandsIncludingNamespacedForms() {
        withRules(rule("no-op", ClientRuleKind.COMMAND).patterns("op").deny().hide().build());
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");
        RootCommandNode<Object> root = tree("op", "minecraft:op", "hub", "msg");

        withRelay.hide(player.player(), root);

        assertEquals(List.of("hub", "msg"), names(root));
    }

    @Test
    void aRuleWithoutHidingLeavesTheTreeAlone() {
        withRules(denyOp());
        RootCommandNode<Object> root = tree("op", "hub");

        withRelay.hide(new ClientPlayer("Steve", 767, "lobby-1").player(), root);

        assertEquals(List.of("hub", "op"), names(root));
    }

    @Test
    void hidingFollowsTheScopeOfTheServerThePlayerIsOn() {
        withRules(rule("no-op", ClientRuleKind.COMMAND).patterns("op").hide().sections("minigames").build());
        RootCommandNode<Object> onMini = tree("op", "hub");
        RootCommandNode<Object> onLobby = tree("op", "hub");

        withRelay.hide(new ClientPlayer("Mini", 767, "minigame-1").player(), onMini);
        withRelay.hide(new ClientPlayer("Lobby", 767, "lobby-1").player(), onLobby);

        assertEquals(List.of("hub"), names(onMini));
        assertEquals(List.of("hub", "op"), names(onLobby));
    }

    @Test
    void hidingDoesNotApplyToStaffWithTheBypassPermission() {
        withRules(rule("no-op", ClientRuleKind.COMMAND).patterns("op").hide().build());
        ClientPlayer admin = new ClientPlayer("Admin", 767, "lobby-1");
        admin.permissions.add(Permissions.CLIENT_RULES_BYPASS);
        RootCommandNode<Object> root = tree("op", "hub");

        withRelay.hide(admin.player(), root);

        assertEquals(List.of("hub", "op"), names(root));
    }

    // ---- the catalogue -----------------------------------------------------------------

    @Test
    void theCatalogueTakesItsCommandsFromTheTreeOnly() {
        CommandCatalogue catalogue = new CommandCatalogue();

        assertTrue(catalogue.learn(tree("hub", "minecraft:op", "Msg", "bad word")));
        assertFalse(catalogue.learn(tree("hub")), "nothing new");

        assertEquals(java.util.Set.of("hub", "minecraft:op", "msg"), catalogue.words());
    }
}
