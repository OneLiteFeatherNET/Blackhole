package net.onelitefeather.blackhole.analytics.spike;

import org.apache.iceberg.DataFile;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.jdbc.JdbcCatalog;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Task group 1 spike (no production code). Iceberg JDBC catalog, Parquet writer, snapshot summary
 * property, row-filter delete, and DuckDB reading the table through its metadata file. Everything
 * runs against a local warehouse in a {@link TempDir} and an embedded H2 catalog database: no S3,
 * no Ceph RGW, no network except the DuckDB iceberg extension download on first run.
 */
class IcebergJdbcCatalogSpikeTest {

    private static final String TABLE = "punishment_events";

    private static final Schema SCHEMA = new Schema(
            required(1, "event_id", Types.StringType.get()),
            required(2, "occurred_at", Types.TimestampType.withZone()),
            optional(3, "owner", Types.StringType.get()));

    private static final PartitionSpec SPEC = PartitionSpec.builderFor(SCHEMA).day("occurred_at").build();

    @TempDir
    Path tempDir;

    private JdbcCatalog catalog;

    @BeforeEach
    void openCatalog() {
        catalog = new JdbcCatalog();
        catalog.initialize("analytics", Map.of(
                "uri", "jdbc:h2:file:" + tempDir.resolve("catalog") + ";AUTO_SERVER=FALSE",
                "jdbc.user", "analytics",
                "jdbc.password", "spike",
                "warehouse", tempDir.resolve("warehouse").toUri().toString()));
    }

    @AfterEach
    void closeCatalog() {
        catalog.close();
    }

    @Test
    void jdbcCatalogCreatesDayPartitionedTable() {
        Table table = createEventsTable();

        assertEquals(1, table.spec().fields().size(), "the table should have exactly one partition field");
        assertEquals("occurred_at_day", table.spec().fields().get(0).name(), "partitioned by day(occurred_at)");
    }

    @Test
    void snapshotSummaryCarriesConsumerSequenceWrittenWithTheCommit() {
        Table table = createEventsTable();
        DataFile file = writeDay(table, List.of(event("e1", "2026-10-01T10:00:00Z")));

        table.newAppend()
                .appendFile(file)
                .set("analytics.consumer.sequence", "42")
                .commit();

        Table reloaded = catalog.loadTable(TableIdentifier.of("analytics", TABLE));
        String sequence = reloaded.currentSnapshot().summary().get("analytics.consumer.sequence");
        assertEquals("42", sequence, "the summary property must be stored in the same snapshot as the data");
    }

    @Test
    void rowFilterDeleteRemovesRowsBeforeTheCutoff() {
        Table table = createEventsTable();
        // One Parquet file per day: a file is in one partition, so a day-aligned cutoff drops whole files.
        table.newAppend()
                .appendFile(writeDay(table, List.of(event("e1", "2026-10-01T10:00:00Z"))))
                .appendFile(writeDay(table, List.of(event("e2", "2026-10-02T10:00:00Z"))))
                .commit();

        table.newDelete()
                .deleteFromRowFilter(Expressions.lessThan("occurred_at", cutoffMicros("2026-10-02T00:00:00Z")))
                .commit();

        Table reloaded = catalog.loadTable(TableIdentifier.of("analytics", TABLE));
        assertNotNull(reloaded.currentSnapshot(), "the delete must create a snapshot");
        assertEquals(1, countRows(reloaded), "only the row on or after the cutoff should remain");
    }

    // Tagged "spike": the DuckDB iceberg extension is downloaded on first run, so this test is not part of the
    // default run. Run it with the spikeTest task (see the PR body).
    @Test
    @Tag("spike")
    void duckDbReadsTableWrittenThroughTheJdbcCatalog() throws Exception {
        Table table = createEventsTable();
        DataFile file = writeDay(table, List.of(
                event("e1", "2026-10-01T10:00:00Z"),
                event("e2", "2026-10-01T11:00:00Z")));
        table.newAppend().appendFile(file).set("analytics.consumer.sequence", "2").commit();

        String metadataLocation = ((HasTableOperations) table).operations().current().metadataFileLocation();

        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute("INSTALL iceberg");
            statement.execute("LOAD iceberg");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM iceberg_scan('" + metadataLocation + "')")) {
                rows.next();
                assertEquals(2L, rows.getLong(1),
                        "DuckDB should read both rows through the metadata file of the JDBC-catalog table");
            }
        }
    }

    /** Iceberg's expression API takes timestamptz literals as epoch microseconds, not as OffsetDateTime. */
    private static long cutoffMicros(String instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, Instant.parse(instant));
    }

    private Table createEventsTable() {
        return catalog.createTable(TableIdentifier.of("analytics", TABLE), SCHEMA, SPEC);
    }

    private static Record event(String id, String occurredAt) {
        GenericRecord record = GenericRecord.create(SCHEMA);
        record.setField("event_id", id);
        record.setField("occurred_at", OffsetDateTime.parse(occurredAt));
        record.setField("owner", "hash");
        return record;
    }

    /** Writes rows that all fall on one day into one Parquet data file of that day's partition. */
    private static DataFile writeDay(Table table, List<Record> rows) {
        OutputFile out = table.io().newOutputFile(table.location() + "/data/" + UUID.randomUUID() + ".parquet");
        PartitionKey key = new PartitionKey(SPEC, SCHEMA);
        // PartitionKey reads the source column in Iceberg's internal representation (micros), not as an OffsetDateTime.
        key.partition(new InternalRecordWrapper(SCHEMA.asStruct()).wrap(rows.get(0)));
        try (DataWriter<Record> writer = Parquet.writeData(out)
                .schema(SCHEMA)
                .createWriterFunc(GenericParquetWriter::create)
                .withSpec(SPEC)
                .withPartition(key)
                .overwrite()
                .build()) {
            rows.forEach(writer::write);
            writer.close();
            return writer.toDataFile();
        } catch (IOException e) {
            throw new IllegalStateException("could not write the spike Parquet file", e);
        }
    }

    private static long countRows(Table table) {
        long count = 0;
        try (var reader = IcebergGenerics.read(table).build()) {
            for (Record ignored : reader) {
                count++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read the spike table", e);
        }
        return count;
    }
}
