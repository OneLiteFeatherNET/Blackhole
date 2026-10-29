package net.onelitefeather.blackhole.backend.intel;

import net.onelitefeather.blackhole.backend.support.InMemoryListGenerationStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListReloadTest {

    private InMemoryListGenerationStore store;
    private IntelClassifierHolder holder;
    private IntelListSourceConfig source;
    private ListReloader reloader;

    @BeforeEach
    void setUp() {
        this.store = new InMemoryListGenerationStore();
        this.holder = new IntelClassifierHolder();
        this.source = new IntelListSourceConfig("x4bnet-vpn");
        this.source.setKind(IntelClass.VPN);
        this.source.setEnabled(true);
        this.reloader = new ListReloader(this.store, this.holder, List.of(this.source), "");
    }

    /** Stores one generation of the source with the given entries and makes it the active one. */
    private void activate(long generation, String cidr) {
        this.store.claimRefresh("x4bnet-vpn", generation * 1_000_000L, 0);
        long opened = this.store.openGeneration("x4bnet-vpn", generation);
        this.store.writeEntries("x4bnet-vpn", opened, List.of(cidr));
        this.store.activate("x4bnet-vpn", opened, generation);
        this.store.deleteOtherGenerations("x4bnet-vpn", opened);
    }

    private boolean listed(String ip) throws Exception {
        return !this.holder.get().classify(InetAddress.getByName(ip), null).isEmpty();
    }

    @Test
    void theFirstReloadLoadsTheActiveEntries() throws Exception {
        activate(1, "198.51.100.0/24");

        assertTrue(this.reloader.reloadIfChanged(), "the first reload loads the active generation");
        assertTrue(listed("198.51.100.42"), "an address of the loaded range is classified");
    }

    @Test
    void anUnchangedActiveGenerationIsNotReloaded() {
        activate(1, "198.51.100.0/24");
        this.reloader.reloadIfChanged();

        assertFalse(this.reloader.reloadIfChanged(), "the same active generation is not loaded again");
    }

    @Test
    void aChangedActiveGenerationIsReloaded() throws Exception {
        activate(1, "198.51.100.0/24");
        this.reloader.reloadIfChanged();

        activate(2, "203.0.113.0/24");

        assertTrue(this.reloader.reloadIfChanged(), "a new active generation triggers a reload");
        assertTrue(listed("203.0.113.9"), "the new range is classified after the reload");
        assertFalse(listed("198.51.100.42"), "the replaced range is no longer classified");
    }

    @Test
    void disablingASourceTriggersAReload() throws Exception {
        activate(1, "198.51.100.0/24");
        this.reloader.reloadIfChanged();

        this.source.setEnabled(false);

        assertTrue(this.reloader.reloadIfChanged(), "a source that is switched off leaves the classifier at the next poll");
        assertFalse(listed("198.51.100.42"), "a disabled source classifies nothing");
        assertEquals(0, this.holder.get().classify(InetAddress.getByName("198.51.100.42"), null).size());
    }
}
