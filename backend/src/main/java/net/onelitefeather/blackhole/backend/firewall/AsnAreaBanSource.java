package net.onelitefeather.blackhole.backend.firewall;

/**
 * The networks of active ASN area bans. Country area bans never reach a feed, by design.
 */
public interface AsnAreaBanSource extends FeedNetworkSource {

    @Override
    default String kind() {
        return "ASN_AREA";
    }
}
