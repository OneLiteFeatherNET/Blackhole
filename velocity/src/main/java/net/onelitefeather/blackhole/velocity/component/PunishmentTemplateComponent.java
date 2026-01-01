package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateDTO;
import net.onelitefeather.blackhole.velocity.component.tag.DurationTag;
import net.onelitefeather.phoca.metadata.Expirable;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public final class PunishmentTemplateComponent {

    private final PunishTemplateDTO template;
    private final PunishProfileDTO profile;

    public PunishmentTemplateComponent(@NotNull PunishTemplateDTO template, PunishProfileDTO profile) {
        this.template = template;
        this.profile = profile;
    }

    public static @NotNull Component of(@NotNull PunishTemplateDTO template, PunishProfileDTO profile) {
        return new PunishmentTemplateComponent(template, profile).asComponent();
    }

    public @NotNull Component asComponent() {
        if (this.profile.getMetaData().containsKey(Expirable.META_DATA_KEY_EXPIRATION_DATE)) {
            var expirationDate = Optional.of(this.profile)
                    .map(PunishProfileDTO::getMetaData)
                    .filter(t -> t.containsKey(Expirable.META_DATA_KEY_EXPIRATION_DATE))
                    .map(Long.class::cast).orElseThrow();
            return MiniMessage.miniMessage().deserialize(this.template.getReason(), DurationTag.resolver(expirationDate));
        }
        return MiniMessage.miniMessage().deserialize(this.template.getReason());
    }
}
