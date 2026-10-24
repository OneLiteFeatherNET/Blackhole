package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.event.player.PlayerModInfoEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.util.ModInfo;
import net.onelitefeather.blackhole.velocity.clientrule.ClientRuleCatalogueReporter;
import net.onelitefeather.blackhole.velocity.clientrule.ClientRuleEnforcer;
import net.onelitefeather.blackhole.velocity.clientrule.ClientTraits;
import net.onelitefeather.blackhole.velocity.clientrule.FingerprintSubmitter;
import net.onelitefeather.blackhole.velocity.clientrule.TraitCollector;
import net.onelitefeather.blackhole.velocity.clientrule.TraitTally;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Feeds the client rules: the trait events (brand, registered channels, legacy mod list), the login, the
 * server connect and the disconnect. Only this class and the command listener import the events; the
 * collector, the engine and the enforcer work on plain values. Velocity does not wait for the trait events,
 * so none of them can hold a connection back, and each arrives whenever the client sends it, also after the
 * first server connect.
 *
 * <p>{@code PlayerClientBrandListener} keeps recording the brand as session info; this listener does not
 * replace it.</p>
 */
public final class ClientRuleListener {

    private final TraitCollector traits;
    private final ClientRuleEnforcer enforcer;
    private final TraitTally tally;
    private final FingerprintSubmitter fingerprints;
    private final ClientRuleCatalogueReporter catalogue;

    public ClientRuleListener(@NotNull TraitCollector traits, @NotNull ClientRuleEnforcer enforcer, @NotNull TraitTally tally,
                              @NotNull FingerprintSubmitter fingerprints, @NotNull ClientRuleCatalogueReporter catalogue) {
        this.traits = traits;
        this.enforcer = enforcer;
        this.tally = tally;
        this.fingerprints = fingerprints;
        this.catalogue = catalogue;
    }

    @Subscribe
    public void onLogin(@NotNull LoginEvent event) {
        this.enforcer.onLogin(event);
    }

    @Subscribe
    public void onServerPreConnect(@NotNull ServerPreConnectEvent event) {
        this.enforcer.onPreConnect(event);
    }

    @Subscribe
    public void onServerPostConnect(@NotNull ServerPostConnectEvent event) {
        this.fingerprints.connected(UUIDConverter.convertToSHA(event.getPlayer().getUniqueId()));
    }

    @Subscribe
    public void onClientBrand(@NotNull PlayerClientBrandEvent event) {
        Player player = event.getPlayer();
        if (player.isActive()) {
            traitArrived(player, this.traits.brand(owner(player), event.getBrand()));
        }
    }

    @Subscribe
    public void onChannelRegister(@NotNull PlayerChannelRegisterEvent event) {
        Player player = event.getPlayer();
        if (player.isActive()) {
            List<String> ids = event.getChannels().stream().map(ChannelIdentifier::getId).toList();
            traitArrived(player, this.traits.channels(owner(player), ids));
        }
    }

    @Subscribe
    public void onModInfo(@NotNull PlayerModInfoEvent event) {
        Player player = event.getPlayer();
        if (player.isActive()) {
            ModInfo info = event.getModInfo();
            List<String> mods = info.getMods().stream().map(mod -> mod.getId() + "@" + mod.getVersion()).toList();
            traitArrived(player, this.traits.mods(owner(player), mods));
        }
    }

    @Subscribe
    public void onDisconnect(@NotNull DisconnectEvent event) {
        String owner = owner(event.getPlayer());
        this.traits.forget(owner);
        this.enforcer.onDisconnect(owner);
        this.fingerprints.forget(owner);
    }

    private void traitArrived(Player player, ClientTraits current) {
        String owner = owner(player);
        this.tally.seen(owner, current);
        this.catalogue.changed();
        this.fingerprints.traitsChanged(owner);
        this.enforcer.onTrait(player);
    }

    private static String owner(Player player) {
        return UUIDConverter.convertToSHA(player.getUniqueId());
    }
}
