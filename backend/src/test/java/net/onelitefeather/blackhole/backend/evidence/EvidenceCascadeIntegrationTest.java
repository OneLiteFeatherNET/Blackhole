package net.onelitefeather.blackhole.backend.evidence;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.service.EvidenceService;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Deleting a punishment record removes its evidence rows (foreign key) and its files (service hook). */
@Tag("integration")
class EvidenceCascadeIntegrationTest extends ApiIntegrationTest {

    private static final String STAFF = UUID.nameUUIDFromBytes("staff".getBytes()).toString();

    @TempDir
    Path files;

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of(
                "blackhole.evidence.files.storage", "local",
                "blackhole.evidence.files.local.path", this.files.toString(),
                "blackhole.evidence.scan.enabled", false
        );
    }

    private long storedFiles() throws IOException {
        try (var walk = Files.walk(this.files)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void deletingThePunishmentRemovesItsEvidenceRowsAndFiles() throws IOException {
        String owner = "c1".repeat(64);
        post("/template", Map.of("key", "cascade", "reason", "Reason", "section", "network",
                "steps", List.of(Map.of("duration", "P1D", "eloCost", 10))));
        Reply applied = post("/punishment/apply", Map.of("owner", owner, "templateKey", "cascade", "source", STAFF));
        @SuppressWarnings("unchecked") String punishment = (String) ((Map<String, Object>) applied.json().get("punishment")).get("identifier");
        EvidenceService service = this.context.getBean(EvidenceService.class);
        assertInstanceOf(EvidenceDTO.Response.class, service.attachFile(
                new EvidenceDTO.UploadRequest(punishment, null, null, "it", null, null, UUID.fromString(STAFF), "shot.png"),
                new ByteArrayInputStream(EvidenceFixtures.png(8, 8))));
        assertEquals(1, storedFiles(), "precondition: the file is stored");
        // The profile's slots reference the punishment, so the profile goes first.
        this.context.getBean(PunishmentProfileRepository.class).deleteById(owner);

        service.deleteForPunishment(punishment);
        PunishmentRepository punishments = this.context.getBean(PunishmentRepository.class);
        punishments.delete(punishments.findById(punishment).orElseThrow());

        assertEquals(0, this.context.getBean(EvidenceItemRepository.class).findByPunishmentIdentifier(punishment).size(), "no evidence row is left");
        assertEquals(0, storedFiles(), "no file is left");
    }
}
