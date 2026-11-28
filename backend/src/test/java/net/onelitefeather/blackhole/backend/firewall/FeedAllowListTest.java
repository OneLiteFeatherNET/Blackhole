package net.onelitefeather.blackhole.backend.firewall;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedAllowListTest {

    private static InetAddress peer(String text) throws Exception {
        return InetAddress.getByName(text);
    }

    @Test
    void anEmptyListAdmitsEveryPeer() throws Exception {
        assertTrue(new FeedAllowList("").allows(peer("198.51.100.44")), "no allow-list means any source");
    }

    @Test
    void aCidrRangeAdmitsItsAddressesOnly() throws Exception {
        FeedAllowList list = new FeedAllowList("10.20.0.0/16");

        assertTrue(list.allows(peer("10.20.3.4")), "an address inside the range is admitted");
        assertFalse(list.allows(peer("10.21.0.1")), "an address outside the range is refused");
    }

    @Test
    void aSingleAddressAdmitsOnlyItself() throws Exception {
        FeedAllowList list = new FeedAllowList("203.0.113.5");

        assertTrue(list.allows(peer("203.0.113.5")), "the listed address is admitted");
        assertFalse(list.allows(peer("203.0.113.6")), "a neighbour is refused");
    }

    @Test
    void aHostNameIsRefusedAtStartUp() {
        assertThrows(IllegalStateException.class, () -> new FeedAllowList("edge.example.net"),
                "the allow-list takes addresses and ranges only");
    }
}
