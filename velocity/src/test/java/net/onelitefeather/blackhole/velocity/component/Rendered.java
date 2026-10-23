package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Renders a message the way a player's client would see it, with the real bundles and the theme, and
 * reads the result. Every call builds its own store, nothing is shared.
 */
final class Rendered {

    private final Component component;

    private Rendered(Component component) {
        this.component = component;
    }

    static Rendered of(Component message, Locale locale) {
        MiniMessageTranslationStore store = MiniMessageTranslationStore.create(Key.key("blackhole", "test"), BlackholeTheme.miniMessage());
        store.defaultLocale(Locale.ENGLISH);
        ClassLoader loader = Rendered.class.getClassLoader();
        store.registerAll(Locale.ENGLISH, ResourceBundle.getBundle("blackhole", Locale.ENGLISH, loader), false);
        store.registerAll(Locale.GERMAN, ResourceBundle.getBundle("blackhole", Locale.GERMAN, loader), false);
        return new Rendered(TranslatableComponentRenderer.usingTranslationSource(store).render(message, locale));
    }

    static Rendered english(Component message) {
        return of(message, Locale.ENGLISH);
    }

    static Rendered german(Component message) {
        return of(message, Locale.GERMAN);
    }

    /** The text without styling, lines separated by {@code \n}. */
    String text() {
        return plain(this.component);
    }

    /** Every click event anywhere in the message. */
    List<ClickEvent> clicks() {
        List<ClickEvent> clicks = new ArrayList<>();
        walk(this.component, node -> {
            if (node.clickEvent() != null) {
                clicks.add(node.clickEvent());
            }
        });
        return clicks;
    }

    /** The values of the click events, in order. */
    List<String> clickValues() {
        return clicks().stream().map(click -> ((ClickEvent.Payload.Text) click.payload()).value()).toList();
    }

    /** The plain text of every hover anywhere in the message. */
    List<String> hoverTexts() {
        List<String> hovers = new ArrayList<>();
        walk(this.component, node -> {
            if (node.hoverEvent() instanceof HoverEvent<?> hover && hover.value() instanceof Component value) {
                hovers.add(plain(value));
            }
        });
        return hovers;
    }

    /** Whether any part of the message carries a hover or a click. */
    boolean hasEvents() {
        boolean[] found = {false};
        walk(this.component, node -> found[0] |= node.clickEvent() != null || node.hoverEvent() != null);
        return found[0];
    }

    private static void walk(Component node, java.util.function.Consumer<Component> visitor) {
        visitor.accept(node);
        node.children().forEach(child -> walk(child, visitor));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
