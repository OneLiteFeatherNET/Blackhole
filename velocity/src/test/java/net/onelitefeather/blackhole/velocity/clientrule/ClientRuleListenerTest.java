package net.onelitefeather.blackhole.velocity.clientrule;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.event.player.PlayerModInfoEvent;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.ModInfo;
import net.onelitefeather.blackhole.client.model.ClientRuleKind;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import net.onelitefeather.blackhole.velocity.listener.ClientRuleListener;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static net.onelitefeather.blackhole.velocity.clientrule.Rules.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trait events reach the collector, the enforcer, the catalogue tally and the fingerprint submitter,
 * in any order and at any time. Velocity types are built directly; no proxy runs.
 *
 * <p>What Velocity 4.3.0 offers, read from its API sources (task 4.1; not captured from a live client):</p>
 * <ul>
 *     <li>{@code Player#getClientBrand()} is {@code @Nullable}. {@code PlayerClientBrandEvent} fires when the client
 *     sends {@code minecraft:brand}; Velocity does not wait for it, and it can arrive after {@code LoginEvent}
 *     and after the first {@code ServerPreConnectEvent}.</li>
 *     <li>{@code PlayerChannelRegisterEvent#getChannels()} is a list of {@code ChannelIdentifier} (use {@code getId()}); it fires
 *     for each {@code minecraft:register} message and Velocity does not wait for it either.</li>
 *     <li>{@code PlayerModInfoEvent} fires only for a Forge client that sends its mod list (legacy FML handshake);
 *     {@code ModInfo.Mod} has {@code getId()} and {@code getVersion()}.</li>
 *     <li>{@code LoginEvent} and {@code ServerPreConnectEvent} are awaited events; {@code InboundConnection#getProtocolVersion()}
 *     is known at login.</li>
 * </ul>
 */
class ClientRuleListenerTest {

    private final TraitCollector traits = new TraitCollector();
    private final FakeClientRuleBackend backend = new FakeClientRuleBackend();
    private final ManualTimer timer = new ManualTimer();
    private final MutableClock clock = new MutableClock();
    private final TraitTally tally = new TraitTally(clock, Duration.ofHours(24));
    private final List<ClientRule> rules = new ArrayList<>();
    private final RuleRegistry registry;
    private final ClientRuleListener listener;
    private final FingerprintSubmitter fingerprints = new FingerprintSubmitter(traits, backend, timer, clock, Duration.ofSeconds(5), Runnable::run);

    ClientRuleListenerTest() {
        this.registry = new RuleRegistry(new RuleRegistry.Source() {
            @Override
            public Optional<List<ClientRule>> fromCache() {
                return Optional.of(rules);
            }

            @Override
            public List<ClientRule> fromApi() {
                return List.of();
            }
        });
        this.registry.reload();
        ClientRuleEnforcer enforcer = new ClientRuleEnforcer(traits, this.registry, new RuleEngine((rule, server) -> true), backend,
                new ClientRuleNotifier(List::of, new ClientRuleNoticeChannel() {
                    @Override
                    public void onClientRuleNotice(java.util.function.Consumer<ClientRuleNotice> listener) {
                    }

                    @Override
                    public boolean publish(ClientRuleNotice notice) {
                        return false;
                    }
                }), Runnable::run);
        ClientRuleCatalogueReporter catalogue = new ClientRuleCatalogueReporter(tally, Set::of, backend, timer, "Proxy-1", 3,
                Duration.ofSeconds(5), Duration.ofMinutes(15));
        this.listener = new ClientRuleListener(traits, enforcer, tally, fingerprints, catalogue);
    }

    private static String owner(ClientPlayer player) {
        return UUIDConverter.convertToSHA(player.uuid);
    }

    @Test
    void brandChannelsAndModsAreCollectedInAnyOrder() {
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");

        listener.onChannelRegister(new PlayerChannelRegisterEvent(player.player(), List.of(MinecraftChannelIdentifier.from("meteor-client:main"))));
        listener.onModInfo(new PlayerModInfoEvent(player.player(), new ModInfo("FML", List.of(new ModInfo.Mod("jei", "4.16")))));
        listener.onClientBrand(new PlayerClientBrandEvent(player.player(), "fabric"));

        ClientTraits collected = traits.traits(owner(player)).orElseThrow();
        assertEquals("fabric", collected.brand());
        assertEquals(Set.of("meteor-client:main"), collected.channels());
        assertEquals(Set.of("jei@4.16"), collected.mods());
    }

    @Test
    void aTraitOfAPlayerWhoAlreadyLeftIsIgnoredSoNothingLeaks() {
        ClientPlayer gone = new ClientPlayer("Gone", 767, "lobby-1");
        gone.active = false;

        listener.onClientBrand(new PlayerClientBrandEvent(gone.player(), "fabric"));

        assertTrue(traits.traits(owner(gone)).isEmpty());
        assertEquals(0, traits.size());
    }

    @Test
    void aLateBrandThatMatchesADenyRuleDisconnectsThePlayer() {
        rules.add(rule("lunar", ClientRuleKind.BRAND).patterns("lunar*").deny().build());
        registry.reload();
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");

        listener.onClientBrand(new PlayerClientBrandEvent(player.player(), "lunarclient"));

        assertEquals(1, player.disconnects.size());
    }

    @Test
    void theDisconnectDropsEverythingKnownAboutThePlayer() {
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");
        listener.onClientBrand(new PlayerClientBrandEvent(player.player(), "fabric"));

        listener.onDisconnect(new DisconnectEvent(player.player(), DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));

        assertTrue(traits.traits(owner(player)).isEmpty());
    }

    @Test
    void theTraitsAreSubmittedForTheFingerprintAfterTheSettlePeriodOfTheFirstConnect() {
        ClientPlayer player = new ClientPlayer("Steve", 767, "lobby-1");
        listener.onChannelRegister(new PlayerChannelRegisterEvent(player.player(), List.of(MinecraftChannelIdentifier.from("meteor-client:main"))));
        fingerprints.connected(owner(player));

        timer.advance(Duration.ofSeconds(5));

        assertEquals(1, backend.fingerprints.size());
    }
}
