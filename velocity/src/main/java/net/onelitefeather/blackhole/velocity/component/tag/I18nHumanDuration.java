package net.onelitefeather.blackhole.velocity.component.tag;

import net.kyori.adventure.text.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

final class I18nHumanDuration {
    private I18nHumanDuration() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static Component format(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return translatable("duration.now");
        }

        long seconds = duration.getSeconds();
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60;

        List<Component> parts = new ArrayList<>(3);

        if (days > 0) parts.add(unit("duration.day", days));
        if (hours > 0) parts.add(unit("duration.hour", hours));
        if (minutes > 0 && days == 0) parts.add(unit("duration.minute", minutes));

        Component joined = joinWithSpace(parts);

        // "in {0}"
        return translatable("duration.in", joined);
    }

    private static Component unit(String baseKey, long n) {
        String key = baseKey + (n == 1 ? ".one" : ".other");
        return translatable(key, text(n));
    }

    private static Component joinWithSpace(List<Component> parts) {
        if (parts.isEmpty()) return Component.empty();
        Component out = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            out = out.append(Component.space()).append(parts.get(i));
        }
        return out;
    }
}
