// The analytics sink: a Micronaut application without an HTTP API. It consumes the domain events from
// the NATS JetStream stream and writes them into Apache Iceberg tables (Parquet on Ceph RGW). Group 1
// adds the skeleton only; the consumer, the sink and the catalog wiring come in later groups.
// The Iceberg and DuckDB versions are pinned on purpose (see openspec/changes/add-analytics-lakehouse).

dependencies {
    annotationProcessor(mn.micronaut.inject.java)

    implementation(mn.micronaut.runtime)
    implementation(mn.micronaut.micrometer.registry.prometheus)
    implementation(libs.nats.jnats)

    // The Iceberg JDBC catalog runs on PostgreSQL (its own database on the CNPG cluster).
    implementation(mn.postgresql)

    // Iceberg: table format, Parquet data files, S3FileIO (path-style for Ceph RGW), JDBC catalog.
    implementation(libs.iceberg.core)
    implementation(libs.iceberg.data)
    implementation(libs.iceberg.parquet)
    implementation(libs.iceberg.aws)
    implementation(libs.parquet.hadoop)

    // Payload columns that have no typed column are stored as one JSON object (the extra column).
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    testImplementation(mn.junit.jupiter.api)
    testImplementation(mn.junit.jupiter.params)
    testRuntimeOnly(mn.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The spike uses an embedded H2 database as the JDBC catalog store and the DuckDB JDBC driver to read the table.
    testImplementation(libs.h2)
    testImplementation(libs.duckdb.jdbc)
    // Iceberg's default FileIO for a local warehouse is HadoopFileIO, which needs Hadoop on the classpath.
    // Production uses S3FileIO and does not need it, so Hadoop stays test-only.
    testImplementation("org.apache.hadoop:hadoop-client-api:3.5.0")
    testRuntimeOnly("org.apache.hadoop:hadoop-client-runtime:3.5.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// The default test run excludes the "spike" tag: those tests download a DuckDB extension and need network.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("spike")
    }
}

// Opt-in run of the tagged spike tests: ./gradlew :analytics-sink:spikeTest
tasks.register<Test>("spikeTest") {
    description = "Runs the spike tests that need network (DuckDB extension download)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("spike")
    }
}
