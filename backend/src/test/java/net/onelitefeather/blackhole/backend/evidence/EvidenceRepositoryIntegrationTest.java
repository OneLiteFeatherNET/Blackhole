package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.context.ApplicationContext;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V8 on a fresh PostgreSQL: both tables exist, Hibernate's schema validation accepts the mapping
 * (the application context would not start otherwise), deleting a punishment deletes its evidence
 * rows but not the audit trail, and the repositories insert, page and filter.
 */
@Tag("integration")
class EvidenceRepositoryIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "ab".repeat(64);

    private ApplicationContext context;
    private EvidenceItemRepository items;
    private EvidenceAuditRepository audit;
    private PunishmentRepository punishments;

    @BeforeEach
    void start() {
        this.context = ApplicationContext.run(contextProperties());
        this.items = this.context.getBean(EvidenceItemRepository.class);
        this.audit = this.context.getBean(EvidenceAuditRepository.class);
        this.punishments = this.context.getBean(PunishmentRepository.class);
    }

    @AfterEach
    void stop() {
        this.context.close();
    }

    private String givenPunishment() {
        String identifier = "punishment-" + UUID.randomUUID();
        this.punishments.save(new PunishmentEntity(identifier, UUID.randomUUID(), OWNER, "network", false, 1L, null, new HashMap<>()));
        return identifier;
    }

    private EvidenceItemEntity givenItem(String punishment, String owner, String correlation, EvidenceStatus status, long createdAt) {
        EvidenceItemEntity item = new EvidenceItemEntity(UUID.randomUUID(), punishment, owner, correlation, EvidenceKind.LINK, "test", createdAt);
        item.setContent("https://example.org/" + item.getIdentifier(), Map.of("description", "d"));
        if (status != EvidenceStatus.VALID) {
            item.markStatus(status, createdAt, null);
        }
        return this.items.save(item);
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    @Test
    void bothTablesExistAfterTheMigration() throws SQLException {
        assertEquals(2, count("select count(*) from information_schema.tables where table_name in ('evidence_items', 'evidence_audit')"),
                "V8 creates evidence_items and evidence_audit");
    }

    @Test
    void deletingAPunishmentDeletesItsEvidenceRowsButKeepsTheAudit() throws SQLException {
        String punishment = givenPunishment();
        EvidenceItemEntity item = givenItem(punishment, OWNER, null, EvidenceStatus.VALID, 10L);
        this.audit.save(new EvidenceAuditEntity(UUID.randomUUID(), item.getIdentifier(), punishment, OWNER,
                EvidenceAuditAction.ATTACHED, null, null, null, null, 10L));

        this.punishments.deleteById(punishment);

        assertFalse(this.items.existsById(item.getIdentifier()), "the evidence row is deleted with its punishment (ON DELETE CASCADE)");
        assertEquals(1, this.audit.findByEvidenceIdentifierOrderByOccurredAt(item.getIdentifier()).size(),
                "the audit entry outlives the evidence");
    }

    @Test
    void itemsOfAPunishmentArePagedOldestFirst() {
        String punishment = givenPunishment();
        for (int i = 0; i < 5; i++) {
            givenItem(punishment, OWNER, null, EvidenceStatus.VALID, 100L + i);
        }

        Page<EvidenceItemEntity> first = this.items.findByPunishmentIdentifierOrderByCreatedAt(punishment, Pageable.from(0, 2));
        Page<EvidenceItemEntity> last = this.items.findByPunishmentIdentifierOrderByCreatedAt(punishment, Pageable.from(2, 2));

        assertEquals(5, first.getTotalSize(), "total across pages");
        assertEquals(List.of(100L, 101L), first.getContent().stream().map(EvidenceItemEntity::getCreatedAt).toList());
        assertEquals(List.of(104L), last.getContent().stream().map(EvidenceItemEntity::getCreatedAt).toList());
    }

    @Test
    void onlyValidItemsAreCounted() {
        String punishment = givenPunishment();
        givenItem(punishment, OWNER, null, EvidenceStatus.VALID, 1L);
        givenItem(punishment, OWNER, null, EvidenceStatus.LIFTED, 2L);
        givenItem(punishment, OWNER, null, EvidenceStatus.REVOKED, 3L);

        assertEquals(1, this.items.countByPunishmentIdentifierAndStatus(punishment, EvidenceStatus.VALID),
                "lifted and revoked evidence is not counted");
        assertEquals(1, this.items.findByPunishmentIdentifierAndStatusOrderByCreatedAt(punishment, EvidenceStatus.LIFTED, Pageable.from(0, 10))
                .getContent().size());
    }

    @Test
    void pendingItemsAreFoundByOwnerAndCorrelationAndByAge() {
        String owner = "cd".repeat(64);
        EvidenceItemEntity pending = givenItem(null, owner, "corr-1", EvidenceStatus.VALID, 50L);
        givenItem(null, owner, "corr-2", EvidenceStatus.VALID, 51L);
        givenItem(null, "ef".repeat(64), "corr-1", EvidenceStatus.VALID, 52L);

        List<EvidenceItemEntity> found = this.items.findByOwnerAndCorrelationIdAndPunishmentIdentifierIsNull(owner, "corr-1");
        assertEquals(List.of(pending.getIdentifier()), found.stream().map(EvidenceItemEntity::getIdentifier).toList(),
                "only the owner's pending item with that correlation");

        List<EvidenceItemEntity> old = this.items.findByPunishmentIdentifierIsNullAndCreatedAtLessThan(51L);
        assertTrue(old.stream().anyMatch(item -> item.getIdentifier().equals(pending.getIdentifier())), "older pending item is found");
        assertTrue(old.stream().allMatch(EvidenceItemEntity::isPending), "claimed evidence is never in the expiry candidates");
    }

    @Test
    void anItemKeepsItsPayloadAndFileMetadata() {
        String punishment = givenPunishment();
        EvidenceItemEntity item = new EvidenceItemEntity(UUID.randomUUID(), punishment, OWNER, null, EvidenceKind.FILE, "mod-tool", 7L);
        item.setFile("2030/01/abc", "shot.png", "image/png", 1234L, "f".repeat(64));
        this.items.save(item);
        EvidenceItemEntity anticheat = new EvidenceItemEntity(UUID.randomUUID(), punishment, OWNER, null, EvidenceKind.ANTICHEAT, "ac", 8L);
        anticheat.setContent(null, Map.of("check", "fly", "violationLevel", 12));
        this.items.save(anticheat);

        EvidenceItemEntity file = this.items.findById(item.getIdentifier()).orElseThrow();
        EvidenceItemEntity record = this.items.findById(anticheat.getIdentifier()).orElseThrow();

        assertEquals("2030/01/abc", file.getFileKey());
        assertEquals(1234L, file.getFileSize());
        assertEquals("fly", record.getPayload().get("check"), "the jsonb payload round-trips");
        assertTrue(this.items.existsByFileKey("2030/01/abc"), "the blob key is referenced by a row");
    }
}
