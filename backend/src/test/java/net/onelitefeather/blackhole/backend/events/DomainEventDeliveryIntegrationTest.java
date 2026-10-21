package net.onelitefeather.blackhole.backend.events;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain events raised from request handling must actually reach the broker: publishing runs on
 * the Netty event loop, where a blocking publish is refused by Reactor and the event is lost.
 */
@Tag("integration")
class DomainEventDeliveryIntegrationTest extends ApiIntegrationTest {

    private static final long WAIT_SECONDS = 15;

    private Connection connection;
    private Channel channel;
    private final LinkedBlockingQueue<String> bodies = new LinkedBlockingQueue<>();
    private ListAppender<ILoggingEvent> publisherLog;
    private Logger publisherLogger;

    @BeforeEach
    void listenOnTheEventsExchange() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        this.connection = factory.newConnection();
        this.channel = this.connection.createChannel();
        this.channel.exchangeDeclare(RabbitTopology.EVENTS_EXCHANGE, "topic", true);
        String queue = this.channel.queueDeclare("", false, true, true, null).getQueue();
        this.channel.queueBind(queue, RabbitTopology.EVENTS_EXCHANGE, "profile.created");
        this.channel.basicConsume(queue, true, (tag, delivery) ->
                this.bodies.add(new String(delivery.getBody(), StandardCharsets.UTF_8)), tag -> { });

        this.publisherLogger = (Logger) LoggerFactory.getLogger(DomainEventPublisher.class);
        this.publisherLog = new ListAppender<>();
        this.publisherLog.start();
        this.publisherLogger.addAppender(this.publisherLog);
    }

    @AfterEach
    void stopListening() throws Exception {
        this.publisherLogger.detachAppender(this.publisherLog);
        this.channel.close();
        this.connection.close();
    }

    @Test
    void creatingAProfileOverHttpDeliversProfileCreatedToTheBroker() throws Exception {
        String owner = "a".repeat(128);

        Reply reply = post("/profile", Map.of("owner", owner, "metaData", Map.of()));

        assertEquals(200, reply.status(), reply.body());
        String body = this.bodies.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(body, "profile.created must arrive on blackhole.events");
        assertTrue(body.contains(owner), "event carries the owner, got " + body);
    }

    @Test
    void publishingFromTheEventLoopLogsNoFailure() throws Exception {
        post("/profile", Map.of("owner", "b".repeat(128), "metaData", Map.of()));
        assertNotNull(this.bodies.poll(WAIT_SECONDS, TimeUnit.SECONDS), "event arrived");

        long errors = this.publisherLog.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.ERROR)).count();
        assertEquals(0, errors, "no publish failure logged, got " + this.publisherLog.list);
    }
}
