package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens.Surface;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.redis.PunishmentSyncMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registering the {@code bh:} tags on the translation store is additive: every text that existed before
 * must render exactly as it did with a plain MiniMessage.
 */
class ThemeStoreCompatibilityTest {

    private static final Pattern ARGUMENT = Pattern.compile("<arg:(\\d+)>");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T20:06:00Z"), ZoneOffset.UTC);

    private static TranslatableComponentRenderer<Locale> renderer(MiniMessageTranslationStore store) {
        store.defaultLocale(Locale.ENGLISH);
        ClassLoader loader = ThemeStoreCompatibilityTest.class.getClassLoader();
        store.registerAll(Locale.ENGLISH, ResourceBundle.getBundle("blackhole", Locale.ENGLISH, loader), false);
        store.registerAll(Locale.GERMAN, ResourceBundle.getBundle("blackhole", Locale.GERMAN, loader), false);
        return TranslatableComponentRenderer.usingTranslationSource(store);
    }

    private static TranslatableComponentRenderer<Locale> plainRenderer() {
        return renderer(MiniMessageTranslationStore.create(Key.key("blackhole", "plain")));
    }

    private static TranslatableComponentRenderer<Locale> themedRenderer() {
        return renderer(MiniMessageTranslationStore.create(Key.key("blackhole", "themed"), BlackholeTheme.miniMessage()));
    }

    /** The key with as many numbered arguments as its text mentions. */
    private static Component translatable(String key, String text) {
        int count = ARGUMENT.matcher(text).results().mapToInt(result -> Integer.parseInt(result.group(1)) + 1).max().orElse(0);
        Component[] arguments = new Component[count];
        for (int i = 0; i < count; i++) {
            arguments[i] = Component.text("argument" + i);
        }
        return Component.translatable(key).arguments(arguments);
    }

    private static PunishmentScreens screens(TranslatableComponentRenderer<Locale> renderer) {
        BlackholeConfig config = new BlackholeConfig();
        config.setAppealUrl("https://example.org/appeal");
        config.setAppealText("our forum");
        config.setTimeZone("Europe/Berlin");
        return new PunishmentScreens(CLOCK, config, TestData.standardRegistry(), renderer);
    }

    @Test
    void everyExistingKeyRendersTheSameWithTheThemeRegistered() {
        TranslatableComponentRenderer<Locale> plain = plainRenderer();
        TranslatableComponentRenderer<Locale> themed = themedRenderer();
        ResourceBundle bundle = ResourceBundle.getBundle("blackhole", Locale.ENGLISH);
        int checked = 0;

        for (String key : bundle.keySet()) {
            if (bundle.getString(key).contains("<bh:")) {
                continue; // written for the theme, a plain MiniMessage cannot render it
            }
            for (Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN}) {
                Component component = translatable(key, bundle.getString(key));

                assertEquals(plain.render(component, locale), themed.render(component, locale),
                        key + " (" + locale + ") must not change when the theme is registered");
                checked++;
            }
        }

        assertTrue(checked > 60, "the check must cover all untouched keys of both languages, covered " + checked);
    }

    @Test
    void everyPunishmentScreenRendersTheSameWithTheThemeRegistered() {
        PunishmentScreens plain = screens(plainRenderer());
        PunishmentScreens themed = screens(themedRenderer());
        long expiresAt = Instant.parse("2026-10-21T21:05:00Z").toEpochMilli();
        String id = "obLD1AAAQACAAAAAAAAAAQ";
        PunishmentSyncMessage[] punishments = {
                new PunishmentSyncMessage(TestData.OWNER, "network", EnforcementKind.BAN.name(), PunishmentSyncMessage.STATE_SET, id, null, "Cheating", expiresAt),
                new PunishmentSyncMessage(TestData.OWNER, "network", EnforcementKind.BAN.name(), PunishmentSyncMessage.STATE_SET, id, null, "Cheating", null),
                new PunishmentSyncMessage(TestData.OWNER, "chat", EnforcementKind.MUTE.name(), PunishmentSyncMessage.STATE_SET, id, null, "Insult", expiresAt),
                new PunishmentSyncMessage(TestData.OWNER, "bedwars", EnforcementKind.SERVER.name(), PunishmentSyncMessage.STATE_SET, id, null, "Griefing", null)};

        for (PunishmentSyncMessage punishment : punishments) {
            for (Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN}) {
                for (Surface surface : Surface.values()) {
                    assertEquals(plain.screen(punishment, locale, surface, true, 2), themed.screen(punishment, locale, surface, true, 2),
                            "the " + punishment.section() + " screen in " + locale + " on " + surface + " must not change");
                }
            }
        }
    }

    @Test
    void aThemeTagInATextRendersWithTheStoresMiniMessage() {
        MiniMessageTranslationStore store = MiniMessageTranslationStore.create(Key.key("blackhole", "tagged"), BlackholeTheme.miniMessage());
        store.defaultLocale(Locale.ENGLISH);
        store.register("test.tagged", Locale.ENGLISH, "<bh:ok>Done <bh:player><arg:0></bh:player>");

        Component rendered = TranslatableComponentRenderer.usingTranslationSource(store)
                .render(Component.translatable("test.tagged").arguments(Component.text("Alex")), Locale.ENGLISH);

        assertEquals("✔ Done Alex", PlainTextComponentSerializer.plainText().serialize(rendered),
                "the theme tags must be resolved, not left as text");
    }
}
