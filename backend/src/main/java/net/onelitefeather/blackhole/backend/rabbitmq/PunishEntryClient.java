package net.onelitefeather.blackhole.backend.rabbitmq;

import io.micronaut.http.annotation.Header;
import io.micronaut.rabbitmq.annotation.Binding;
import io.micronaut.rabbitmq.annotation.RabbitClient;
import net.onelitefeather.blackhole.backend.rabbitmq.model.PunishEntryMsg;

@RabbitClient
public interface PunishEntryClient {

    @Binding("punish-entry")
    void send(@Header("x-message-ttl") Long ttl, PunishEntryMsg msg);

}
