package net.onelitefeather.blackhole.velocity.component.tag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.time.Duration;

public final class DurationTag {
    private DurationTag() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * @return a translatable, human readable form of the duration ("in 2 days 3 hours")
     */
    public static Component format(Duration duration) {
        return I18nHumanDuration.format(duration);
    }

    /**
     * @return the remaining time as "2 days 3 hours remaining", localized
     */
    public static Component remaining(Duration duration) {
        return I18nHumanDuration.remaining(duration);
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
