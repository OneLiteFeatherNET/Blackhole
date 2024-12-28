package net.onelitefeather.blackhole.backend.rabbitmq;

import io.micronaut.rabbitmq.annotation.Binding;
import io.micronaut.rabbitmq.annotation.RabbitClient;
import io.micronaut.rabbitmq.annotation.RabbitProperty;
import net.onelitefeather.blackhole.backend.rabbitmq.model.PunishEntryMsg;

import java.util.concurrent.CompletableFuture;

@RabbitClient("blackhole.exchange")
public interface PunishEntryClient {

    @Binding("punish-entry")
    CompletableFuture<Void> send(@RabbitProperty("expiration") Long ttl, PunishEntryMsg msg);

}
