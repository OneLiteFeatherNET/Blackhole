package net.onelitefeather.blackhole.backend.rabbitmq.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record PunishEntryMsg(
        String punishEntryId,
        String profileId
) {
}
