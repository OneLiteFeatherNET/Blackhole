package net.onelitefeather.blackhole.backend.banid;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.support.BanIdTestBed;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BanIdBackfillTest {

    private final InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
            InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier).build();
    private final BanIdTestBed bed = new BanIdTestBed(this.punishments.repository(), new BanIdGenerator(new Random(11)));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final BanIdBackfill backfill = new BanIdBackfill(this.bed.store, this.bed.service, this.meters);

    private void givenPunishments(int count) {
        for (int i = 0; i < count; i++) {
            this.bed.store.givenPunishment("p" + i);
        }
    }

    private double missing() {
        return this.meters.get("blackhole.banid.missing").gauge().value();
    }

    @Test
    void everyPunishmentWithoutABanIdGetsOneAcrossSeveralBatches() {
        givenPunishments(BanIdBackfill.BATCH * 2 + 17);
        assertEquals(BanIdBackfill.BATCH * 2 + 17, missing(), "the gauge counts the missing ones");

        int issued = this.backfill.run();

        assertEquals(BanIdBackfill.BATCH * 2 + 17, issued);
        assertEquals(0, missing());
        assertEquals(issued, this.bed.store.hashes().size());
    }

    @Test
    void aSecondRunChangesNothing() {
        givenPunishments(30);
        this.backfill.run();
        byte[] hash = this.bed.store.hashes().get("p3").clone();
        byte[] encrypted = this.bed.store.encrypted().get("p3").clone();
        int before = this.bed.store.hashes().size();

        int second = this.backfill.run();

        assertEquals(0, second, "nothing left to issue");
        assertEquals(before, this.bed.store.hashes().size());
        assertArrayEquals(hash, this.bed.store.hashes().get("p3"), "the Ban ID did not change");
        assertArrayEquals(encrypted, this.bed.store.encrypted().get("p3"));
    }

    @Test
    void aRunOnlyTouchesPunishmentsWithoutABanId() {
        givenPunishments(5);
        this.bed.issueFor("p0");
        byte[] existing = this.bed.store.hashes().get("p0").clone();

        assertEquals(4, this.backfill.run());

        assertArrayEquals(existing, this.bed.store.hashes().get("p0"));
        assertEquals(5, this.bed.store.hashes().values().stream().map(Arrays::toString).distinct().count(), "all distinct");
    }

    @Test
    void anEmptyTableIsFine() {
        assertEquals(0, this.backfill.run());
        assertEquals(0, missing());
    }
}
