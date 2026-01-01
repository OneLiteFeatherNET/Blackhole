package net.onelitefeather.blackhole.velocity.component.tag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.time.Duration;

public final class DurationTag {
    private DurationTag() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static TagResolver resolver(long endMillis, long nowMillis) {
        final Duration d = Duration.ofMillis(endMillis - nowMillis);
        final Component text = I18nHumanDuration.format(d);
        return TagResolver.resolver("duration", Tag.inserting(text));
    }

    public static TagResolver resolver(long endMillis) {
        return resolver(endMillis, System.currentTimeMillis());
    }
}
