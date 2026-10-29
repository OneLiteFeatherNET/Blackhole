package net.onelitefeather.blackhole.backend.firewall;

/**
 * The networks of active ASN area bans (openspec add-join-guard). Join guard is not on main yet, so the only implementation
 * contributes nothing; the follow-up that merges join guard replaces it with the real source behind this interface.
 * Country area bans never reach a feed, by design.
 */
public interface AsnAreaBanSource extends FeedNetworkSource {

    @Override
    default String kind() {
        return "ASN_AREA";
    }
}
