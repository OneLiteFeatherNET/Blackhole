package net.onelitefeather.blackhole.velocity.clientrule;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.onelitefeather.blackhole.velocity.component.ClientRuleMessages;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRuleNoticeTest {

    private static final ClientRuleNotice NOTICE = new ClientRuleNotice("Meteor", "Steve", "CHANNEL", "DENY", true);

    /** A channel nobody listens to, as when Redis is down. */
    private static final class DeadChannel implements ClientRuleNoticeChannel {
        final List<Consumer<ClientRuleNotice>> listeners = new ArrayList<>();

        @Override
        public void onClientRuleNotice(Consumer<ClientRuleNotice> listener) {
            this.listeners.add(listener);
        }

        @Override
        public boolean publish(ClientRuleNotice notice) {
            return false;
        }
    }

    @Test
    void theNoticeSurvivesTheWireForm() {
        assertEquals(NOTICE, ClientRuleNotice.decode(ClientRuleNotice.encode(NOTICE)).orElseThrow());
    }

    @Test
    void anUnreadableOrIncompleteNoticeIsIgnored() {
        assertTrue(ClientRuleNotice.decode(null).isEmpty());
        assertTrue(ClientRuleNotice.decode("not json").isEmpty());
        assertTrue(ClientRuleNotice.decode("{\"rule\":\"x\"}").isEmpty());
    }

    @Test
    void unknownFieldsFromANewerProxyAreIgnored() {
        String json = ClientRuleNotice.encode(NOTICE).replace("}", ",\"later\":1}");

        assertEquals(NOTICE, ClientRuleNotice.decode(json).orElseThrow());
    }

    @Test
    void onlyStaffWithTheNotifyPermissionAreTold() {
        ClientPlayer staff = new ClientPlayer("Staff", 767, "lobby");
        staff.permissions.add(Permissions.CLIENT_RULES_NOTIFY);
        ClientPlayer player = new ClientPlayer("Player", 767, "lobby");

        new ClientRuleNotifier(() -> List.of(staff.player(), player.player()), new DeadChannel()).deliver(NOTICE);

        assertEquals(1, staff.messages.size());
        assertTrue(player.messages.isEmpty());
    }

    @Test
    void whenNobodyListensTheAnnouncingProxyDeliversItItself() {
        ClientPlayer staff = new ClientPlayer("Staff", 767, "lobby");
        staff.permissions.add(Permissions.CLIENT_RULES_NOTIFY);

        new ClientRuleNotifier(() -> List.of(staff.player()), new DeadChannel()).announce(NOTICE);

        assertEquals(1, staff.messages.size());
    }

    @Test
    void theNoticeNamesTheRuleThePlayerTheKindTheActionAndWhetherItWasEnforced() {
        Component message = ClientRuleMessages.notice(NOTICE);

        TranslatableComponent translatable = (TranslatableComponent) message;
        assertEquals("clientrule.notice", translatable.key());
        assertEquals(5, translatable.arguments().size());
        assertEquals("clientrule.kind.channel", ((TranslatableComponent) translatable.arguments().get(2).asComponent()).key());
        assertEquals("clientrule.action.deny", ((TranslatableComponent) translatable.arguments().get(3).asComponent()).key());
        assertEquals("clientrule.enforced.yes", ((TranslatableComponent) translatable.arguments().get(4).asComponent()).key());
    }
}
