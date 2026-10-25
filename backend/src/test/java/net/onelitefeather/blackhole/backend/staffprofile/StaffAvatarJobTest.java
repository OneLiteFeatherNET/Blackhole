package net.onelitefeather.blackhole.backend.staffprofile;

import net.onelitefeather.blackhole.backend.support.InMemoryStaffAvatars;
import net.onelitefeather.blackhole.backend.support.InMemoryStaffProfiles;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The avatar job against a fake head source: no network, fixed clock, in-memory store. */
class StaffAvatarJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);
    private static final UUID OID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MINECRAFT = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID OTHER_MINECRAFT = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private final InMemoryStaffProfiles profiles = new InMemoryStaffProfiles();
    private final InMemoryStaffAvatars avatars = new InMemoryStaffAvatars();
    private final StaffAvatarKeys keys = new StaffAvatarKeys("unit-test-secret");
    private final FakeSource source = new FakeSource();
    private final StaffAvatarJob job = new StaffAvatarJob(this.profiles, this.avatars, this.keys, this.source, CLOCK);

    @Test
    void aLinkFetchesAHeadThatIsStoredAsA64PxPng() throws IOException {
        this.source.answer = StaffHeadRenderer.head64(StaffHeadRendererTest.skin(64, 64));

        this.job.onLinked(OID, MINECRAFT);

        byte[] png = this.job.png(this.keys.keyFor(MINECRAFT)).orElseThrow();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(64, image.getWidth());
        assertEquals(64, image.getHeight());
    }

    @Test
    void aLinkStoresTheAvatarKeyOnTheProfileAndNeverTheUuid() {
        this.source.answer = StaffHeadRenderer.head64(StaffHeadRendererTest.skin(64, 64));

        this.job.onLinked(OID, MINECRAFT);

        String key = this.profiles.find(OID).orElseThrow().avatarKey();
        assertEquals(this.keys.keyFor(MINECRAFT), key);
        assertFalse(key.contains(MINECRAFT.toString()));
    }

    @Test
    void aFailedFetchKeepsTheOldHead() {
        byte[] first = StaffHeadRenderer.head64(StaffHeadRendererTest.skin(64, 64));
        this.source.answer = first;
        this.job.onLinked(OID, MINECRAFT);
        this.source.failure = new IllegalStateException("session server down");

        this.job.refresh(OID, MINECRAFT);

        assertArrayEquals(first, this.job.png(this.keys.keyFor(MINECRAFT)).orElseThrow());
    }

    @Test
    void anAccountWithoutASkinHasNoHeadAndKeepsItsInitials() {
        this.source.answer = null;

        this.job.onLinked(OID, MINECRAFT);

        assertFalse(this.job.png(this.keys.keyFor(MINECRAFT)).isPresent());
        assertEquals(this.keys.keyFor(MINECRAFT), this.profiles.find(OID).orElseThrow().avatarKey());
    }

    @Test
    void anUnlinkDeletesTheHeadAndClearsTheKey() {
        this.source.answer = StaffHeadRenderer.head64(StaffHeadRendererTest.skin(64, 64));
        this.job.onLinked(OID, MINECRAFT);
        String key = this.keys.keyFor(MINECRAFT);

        this.job.onUnlinked(OID);

        assertFalse(this.job.png(key).isPresent());
        assertNull(this.profiles.find(OID).orElseThrow().avatarKey());
    }

    @Test
    void aLinkToAnotherAccountDeletesTheHeadOfThePreviousOne() {
        this.source.answer = StaffHeadRenderer.head64(StaffHeadRendererTest.skin(64, 64));
        this.job.onLinked(OID, MINECRAFT);
        String previous = this.keys.keyFor(MINECRAFT);

        this.job.onLinked(OID, OTHER_MINECRAFT);

        assertFalse(this.job.png(previous).isPresent());
        assertTrue(this.job.png(this.keys.keyFor(OTHER_MINECRAFT)).isPresent());
    }

    @Test
    void aRefreshOfAStaleUuidDoesNotFetchAnything() {
        this.source.answer = StaffHeadRenderer.head64(StaffHeadRendererTest.skin(64, 64));
        this.job.onLinked(OID, MINECRAFT);
        int before = this.source.calls;

        this.job.refresh(OID, OTHER_MINECRAFT);

        assertEquals(before, this.source.calls);
    }

    /** A head source that answers what the test sets; it records each call. */
    static final class FakeSource implements StaffAvatarSource {
        byte[] answer;
        RuntimeException failure;
        int calls;
        final List<UUID> requested = new ArrayList<>();

        @Override
        public Optional<byte[]> headPng(UUID minecraftUuid) {
            this.calls++;
            this.requested.add(minecraftUuid);
            if (this.failure != null) {
                throw this.failure;
            }
            return Optional.ofNullable(this.answer);
        }
    }
}
