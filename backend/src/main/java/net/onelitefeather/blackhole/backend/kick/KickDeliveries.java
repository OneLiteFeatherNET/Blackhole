package net.onelitefeather.blackhole.backend.kick;

import net.onelitefeather.blackhole.backend.kick.KickStore.Delivery;

import java.util.List;

/**
 * What the proxies reported for one kick, as counts. A proxy is counted once however often it
 * answered, because the store keeps one row per proxy.
 *
 * @param acknowledged how many proxies answered
 * @param found        how many of them found the player (a mass kick: had anybody on the target)
 * @param moved        how many of them moved or disconnected the player (a mass kick: at least one player)
 */
public record KickDeliveries(int acknowledged, int found, int moved) {

    public static KickDeliveries of(List<Delivery> deliveries) {
        return new KickDeliveries(
                deliveries.size(),
                (int) deliveries.stream().filter(Delivery::found).count(),
                (int) deliveries.stream().filter(Delivery::moved).count());
    }

    /** The number of proxies that delivered the kick. */
    public int deliveredCount() {
        return this.moved;
    }
}
