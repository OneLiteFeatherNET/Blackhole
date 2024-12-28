package net.onelitefeather.blackhole.backend.rabbitmq.model;

public record PunishEntryMsg(
        String punishEntryId,
        String profileId
) {
}
