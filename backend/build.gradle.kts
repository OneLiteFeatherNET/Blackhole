plugins {
    alias(libs.plugins.micronaut.application)
    alias(libs.plugins.micronaut.aot)
}

dependencies {
    annotationProcessor(mn.micronaut.serde.processor)
    annotationProcessor(mn.micronaut.http.validation)
    annotationProcessor(mn.micronaut.openapi)
    annotationProcessor(mn.micronaut.security.annotations)
    compileOnly(mn.micronaut.openapi.annotations)
    implementation(mn.micronaut.http.validation)
    implementation(project(":phoca"))
    // The chat Markdown subset: one parser for the conversation readers, the protocol and the Discord relay.
    implementation(project(":markdown"))

    implementation(mn.micronaut.runtime)
    implementation(mn.validation)
    implementation(mn.micronaut.http.client.jdk)
    // Otis is the team's own player master-data service - its generated client is published to
    // the same OneLiteFeatherRepository declared in settings.gradle.kts, the same way this
    // project publishes its own `client` module. Its Maven groupId is the shared org-wide
    // "net.onelitefeather" (from Otis's rootProject.group), NOT "net.onelitefeather.otis" -
    // that longer name is only the Java package prefix its OpenAPI generator emits
    // (net.onelitefeather.otis.client.*), a separate, unrelated setting.
    implementation("net.onelitefeather:otis-client:1.17.0")
    // Entra ID access tokens: resource server only, validated against a JWKS (no login flow, no session).
    implementation(mn.micronaut.security)
    implementation(mn.micronaut.security.jwt)
    implementation(mn.micronaut.management)
    implementation(mn.micronaut.micrometer.core)
    implementation(mn.micronaut.micrometer.registry.prometheus)
    implementation(mn.micronaut.data.spring.jpa)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.hibernate.jpa)
    implementation(mn.micronaut.flyway)
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    implementation(libs.nats.jnats)
    implementation(mn.micronaut.redis.lettuce)
    implementation(mn.micronaut.hibernate.validator)
    implementation(mn.micronaut.data.tx.hibernate)
    implementation(mn.micronaut.jdbc.hikari)
    implementation(mn.postgresql)
    implementation(mn.snakeyaml)
    implementation(mn.logback.core)
    implementation(mn.logback.classic)
    implementation(mn.jackson.core)
    implementation(mn.jackson.databind)
    implementation(mn.jackson.datatype.jsr310)

    // Distributed tracing (OpenTelemetry). Spans/export are only active when
    // OTEL_TRACES_EXPORTER=otlp is set (prod/Docker) — see application.yml.
    implementation(mn.micronaut.tracing.opentelemetry.http)
    implementation(mn.micronaut.tracing.opentelemetry.jdbc)
    implementation(libs.opentelemetry.exporter.otlp)

    // Structured JSON logging for log aggregation + trace/log correlation.
    // logstash encoder renders JSON; the OTel MDC appender injects trace_id/span_id.
    implementation(libs.logstash.logback.encoder)
    implementation(libs.opentelemetry.logback.mdc)
    // Enables the <if>/<then>/<else> conditional in logback.xml.
    runtimeOnly(libs.janino)

    // Evidence file storage on S3-compatible object storage (Apache-2.0). The CRT/Netty async
    // clients are not needed: the blocking Apache client is enough for streamed uploads/ranges.
    // Offline ASN and country lookup over MaxMind DB files (DB-IP Lite or GeoLite2). Reads local files only.
    implementation("com.maxmind.db:maxmind-db:4.2.0")
    // Argon2id verification of the ban-list client passwords (publicbans/auth). Bouncy Castle 1.84, released well over two weeks ago.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation(libs.awssdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    testImplementation(mn.junit.jupiter.api)
    testImplementation(mn.junit.jupiter.params)
    testRuntimeOnly(mn.junit.jupiter.engine)
    testImplementation(mn.micronaut.test.junit5)
    // InMemorySpanExporter for the tracing tests; no collector is involved.
    testImplementation(libs.opentelemetry.sdk.testing)
    // Integration tests run against real PostgreSQL/NATS/Redis containers (Testcontainers).
    testImplementation(mn.testcontainers.postgres)
    testImplementation(mn.testcontainers.redis)
    testImplementation("org.testcontainers:junit-jupiter")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("net.onelitefeather.blackhole.backend.BlackholeApplication")
}

graalvmNative.toolchainDetection.set(false)

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("net.theevilreaper.hysterix.backend.*")
    }
    aot {
        // Please review carefully the optimizations enabled below
        // Check https://micronaut-projects.github.io/micronaut-aot/latest/guide/ for more details
        optimizeServiceLoading.set(false)
        convertYamlToJava.set(false)
        precomputeOperations.set(true)
        cacheEnvironment.set(true)
        optimizeClassLoading.set(true)
        deduceEnvironment.set(true)
        optimizeNetty.set(true)
        // Keep logback.xml parsed at runtime so the env-driven JSON/plain switch
        // and ${...} substitutions work in the optimized (Docker/prod) jar.
        replaceLogbackXml.set(false)
    }
}

tasks {
    withType(JavaCompile::class.java) {
        options.encoding = "UTF-8"
        options.forkOptions.jvmArgs = listOf("-Dmicronaut.openapi.views.spec=rapidoc.enabled=true,openapi-explorer.enabled=true,swagger-ui.enabled=true,swagger-ui.theme=flattop")
    }
}

// The Docker image runs as an unprivileged user (uid 10001, like the Discord bot) so the Helm chart can
// enforce runAsNonRoot. The application files stay owned by root and are only readable by that user, so
// the process cannot rewrite its own code. The JVM only needs /tmp to be writable; the chart mounts an
// emptyDir there because it also runs the container with a read-only root file system.
tasks.named<io.micronaut.gradle.docker.MicronautDockerfile>("optimizedDockerfile") {
    instruction("RUN groupadd --system --gid 10001 blackhole && useradd --system --uid 10001 --gid blackhole --no-create-home blackhole")
    instruction("USER 10001:10001")
}

// Integration tests (@Tag("integration")) need Docker for Testcontainers. Run the rest without it
// via ./gradlew :backend:test -PexcludeTags=integration.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("excludeTags").orNull?.let { tags ->
        useJUnitPlatform { excludeTags(tags) }
    }
    // Integration tests start a full application context each; the default test heap runs out of memory
    // in CI when several of them share one test executor.
    maxHeapSize = "2g"
}
