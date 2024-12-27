plugins {
    alias(libs.plugins.micronaut.application)
    alias(libs.plugins.micronaut.aot)
}

group = "net.onelitefeather.blackhole.backend"
version = "1.0.0"

dependencies {
    annotationProcessor(mn.micronaut.serde.processor)
    annotationProcessor(mn.micronaut.http.validation)

    implementation(mn.micronaut.runtime)
    implementation(mn.validation)
    implementation(mn.micronaut.http.client.jdk)
    implementation(mn.micronaut.cache.caffeine)
    implementation(mn.micronaut.serde.jackson)

    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("net.onelitefeather.blackhole.backend.Application")
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
