plugins {
    alias(libs.plugins.micronaut.application)
    alias(libs.plugins.micronaut.aot)
    `maven-publish`
}

dependencies {
    annotationProcessor(mn.micronaut.serde.processor)
    annotationProcessor(mn.micronaut.http.validation)
    annotationProcessor(mn.micronaut.openapi)
    compileOnly(mn.micronaut.openapi.annotations)
    implementation(mn.micronaut.http.validation)
    implementation(project(":phoca"))

    implementation(mn.micronaut.runtime)
    implementation(mn.validation)
    implementation(mn.micronaut.http.client.jdk)
    // Otis (net.onelitefeather.otis) is the team's own player master-data service - its
    // generated client is published to the same OneLiteFeatherRepository declared in
    // settings.gradle.kts, the same way this project publishes its own `client` module.
    implementation("net.onelitefeather.otis:otis-client:1.16.0")
    implementation(mn.micronaut.management)
    implementation(mn.micronaut.micrometer.core)
    implementation(mn.micronaut.micrometer.registry.prometheus)
    implementation(mn.micronaut.cache.caffeine)
    implementation(mn.micronaut.data.spring.jpa)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.hibernate.jpa)
    implementation(mn.micronaut.liquibase)
    implementation(mn.micronaut.rabbitmq)
    implementation(mn.micronaut.redis.lettuce)
    implementation(mn.micronaut.hibernate.validator)
    implementation(mn.micronaut.data.tx.hibernate)
    implementation(mn.micronaut.jdbc.hikari)
    implementation(mn.mariadb.java.client)
    implementation(mn.postgresql)
    implementation(mn.h2)
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

    testImplementation(mn.junit.jupiter.api)
    testRuntimeOnly(mn.junit.jupiter.engine)
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

publishing {
    publications.create<MavenPublication>("maven") {
        artifact(project.tasks.optimizedJitJar)
        artifact(project.tasks.optimizedRunnerJitJar)
        artifact(project.tasks.runnerJar)
        artifact(project.tasks.jar)
        artifact(project.tasks.optimizedDistTar)
        artifact(project.tasks.optimizedDistZip)

        version = rootProject.version as String
        artifactId = "blackhole-backend"
        groupId = rootProject.group as String
        pom {
            name = "Blackhole"
            description = "A backend server for managing different kind of punishments"
            url = "https://github.com/OneLiteFeatherNET/Blackhole"
            licenses {
                license {
                    name = "AGPL-3.0"
                    url = "https://www.gnu.org/licenses/agpl-3.0.en.html"
                }
            }
            developers {
                developer {
                    id = "themeinerlp"
                    name = "Phillipp Glanz"
                    email = "p.glanz@madfix.me"
                }
                developer {
                    id = "theEvilReaper"
                    name = "Steffen Wonning"
                    email = "steffenwx@gmail.com"
                }
            }
            scm {
                connection = "scm:git:git://github.com:OneLiteFeatherNET/Blackhole.git"
                developerConnection = "scm:git:ssh://git@github.com:OneLiteFeatherNET/Blackhole.git"
                url = "https://github.com/OneLiteFeatherNET/Blackhole"
            }
        }
    }

    repositories {
        maven {
            authentication {
                credentials(PasswordCredentials::class) {
                    // Those credentials need to be set under "Settings -> Secrets -> Actions" in your repository
                    username = System.getenv("ONELITEFEATHER_MAVEN_USERNAME")
                    password = System.getenv("ONELITEFEATHER_MAVEN_PASSWORD")
                }
            }

            name = "OneLiteFeatherRepository"
            val releasesRepoUrl = uri("https://repo.onelitefeather.dev/onelitefeather-releases")
            val snapshotsRepoUrl = uri("https://repo.onelitefeather.dev/onelitefeather-snapshots")
            url = if (version.toString().contains("BETA") || version.toString().contains("ALPHA") || version.toString().contains("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
        }
    }
}
