package net.onelitefeather.blackhole.velocity.enforcement;

import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuteNoticesTest {

    private static final Duration COOLDOWN = Duration.ofSeconds(30);
    private static final String ID_1 = "a1b2c3d4-0000-4000-8000-000000000001";
    private static final String ID_2 = "e5f60718-0000-4000-8000-000000000002";

    private final MutableClock clock = new MutableClock();
    private final MuteNotices notices = new MuteNotices(screens(), new MuteNoticeCooldown(clock, COOLDOWN));

    private PunishmentScreens screens() {
        var store = MiniMessageTranslationStore.create(Key.key("blackhole", "test"), BlackholeTheme.miniMessage());
        store.defaultLocale(Locale.ENGLISH);
        ClassLoader loader = MuteNoticesTest.class.getClassLoader();
        store.registerAll(Locale.ENGLISH, ResourceBundle.getBundle("blackhole", Locale.ENGLISH, loader), false);
        return new PunishmentScreens(clock, new BlackholeConfig(), TestData.standardRegistry(),
                TranslatableComponentRenderer.usingTranslationSource(store), identifier -> java.util.Optional.of("7K3M9QXA"));
    }

    private static PunishmentSyncMessage mute(String id) {
        return TestData.active("chat", EnforcementKind.MUTE, id, null);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private String notice(PunishmentSyncMessage mute) {
        return plain(this.notices.notice(TestData.OWNER, mute, Locale.ENGLISH, 0));
    }

    private static boolean isFull(String text) {
        return text.contains("Reason");
    }

    /** A sync handler whose proxy fails the test on any call: a mute sync must not reach a player. */
    private PunishmentSyncHandler syncHandler(PunishmentMirror mirror) {
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(MuteNoticesTest.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class}, (p, method, args) -> {
                    throw new AssertionError("a mute sync must not touch the proxy: " + method.getName());
                });
        EnforcementActions actions = new EnforcementActions(
                proxy, TestData.standardRegistry(), null, new BlackholeConfig(), clock, screens(),
                new net.onelitefeather.blackhole.velocity.cloudnet.ServerResolver(
                        new net.onelitefeather.blackhole.velocity.cloudnet.NoCloudNetView(), TestData.standardRegistry(), message -> {}));
        return new PunishmentSyncHandler(mirror, actions);
    }

    @Test
    void applySyncEchoAndFirstChatShowExactlyOneFullNotice() {
        PunishmentSyncMessage mute = mute(ID_1);

        String applied = notice(mute);
        syncHandler(new PunishmentMirror()).accept(mute);
        String firstChat = notice(mute);

        assertTrue(isFull(applied), "the command shows the full notice, got:\n" + applied);
        assertFalse(isFull(firstChat), "the first chat attempt only gets the reminder, got:\n" + firstChat);
    }

    @Test
    void aNewPunishmentIdShowsTheFullNoticeAgain() {
        notice(mute(ID_1));

        assertTrue(isFull(notice(mute(ID_2))), "a different mute is a new notice");
    }

    @Test
    void theReminderFollowsTheFullNoticeWithinTheCooldown() {
        PunishmentSyncMessage mute = mute(ID_1);
        notice(mute);
        clock.advance(Duration.ofSeconds(10));

        assertEquals("Blackhole » You have been muted · Duration: permanent · Ban ID: 7K3M9QXA", notice(mute));
    }

    @Test
    void theFullNoticeComesBackAfterTheCooldown() {
        PunishmentSyncMessage mute = mute(ID_1);
        notice(mute);
        clock.advance(COOLDOWN);

        assertTrue(isFull(notice(mute)), "the cooldown is over");
    }

    @Test
    void aPlayerWhoLeftGetsTheFullNoticeOnReturn() {
        PunishmentSyncMessage mute = mute(ID_1);
        notice(mute);
        this.notices.forget(TestData.OWNER);

        assertTrue(isFull(notice(mute)), "nothing is remembered after the player left");
    }

    @Test
    void theFullNoticeCarriesThePrefix() {
        assertTrue(notice(mute(ID_1)).startsWith("Blackhole » You have been muted"), "the header line has the prefix");
    }
}
