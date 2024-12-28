plugins {
    alias(libs.plugins.micronaut.application)
    alias(libs.plugins.micronaut.aot)
}

group = "net.onelitefeather.blackhole.backend"
version = "1.0.0"

dependencies {
    annotationProcessor(mn.micronaut.serde.processor)
    annotationProcessor(mn.micronaut.http.validation)
    annotationProcessor(mn.micronaut.openapi)
    compileOnly(mn.micronaut.openapi.annotations)
    implementation(mn.micronaut.http.validation)

    implementation(project(":api"))

    implementation(mn.micronaut.runtime)
    implementation(mn.validation)
    implementation(mn.micronaut.http.client.jdk)
    implementation(mn.micronaut.cache.caffeine)
    implementation(mn.micronaut.data.spring.jpa)
    implementation(mn.micronaut.rabbitmq)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.hibernate.jpa)
    implementation(mn.micronaut.hibernate.validator)
    implementation(mn.micronaut.data.tx.hibernate)
    implementation(mn.micronaut.jdbc.hikari)
    implementation(mn.mariadb.java.client)
    implementation(mn.postgresql)
    implementation(mn.h2)
    implementation(mn.log4j)
    implementation(mn.slf4j.api)
    implementation(mn.slf4j.simple)
    implementation(mn.jackson.core)
    implementation(mn.jackson.databind)
    implementation(mn.jackson.datatype.jsr310)

    testImplementation(mn.junit.jupiter.api)
    testRuntimeOnly(mn.junit.jupiter.engine)
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
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
    }
}

tasks {
    withType(JavaCompile::class.java) {
        options.encoding = "UTF-8"
        options.forkOptions.jvmArgs = listOf("-Dmicronaut.openapi.views.spec=rapidoc.enabled=true,openapi-explorer.enabled=true,swagger-ui.enabled=true,swagger-ui.theme=flattop")
    }
}
