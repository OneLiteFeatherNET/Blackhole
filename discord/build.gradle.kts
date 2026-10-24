plugins {
    application
    alias(libs.plugins.shadow)
}

// The Discord bot: a plain Java application (a long-lived gateway client with a handful of
// collaborators, not a Micronaut app). It is stateless; everything it needs it asks the backend for.
application {
    mainClass.set("net.onelitefeather.blackhole.discord.Main")
}

dependencies {
    implementation(project(":client"))

    // The same Markdown parser and renderers the backend uses, so Discord shows what the portal shows.
    implementation(project(":markdown"))

    // Voice is not used: no audio, so the native opus-java dependency stays out of the image.
    implementation(libs.jda) {
        exclude(module = "opus-java")
    }

    // Events about new reports, appeals and cases come from the backend's JetStream stream.
    implementation(libs.nats.jnats)

    // client's jackson-databind is `implementation`-scoped there, so it isn't exposed transitively.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)

    implementation(libs.jetbrains.annotations)
    implementation(mn.logback.classic)

    testImplementation(mn.junit.jupiter.api)
    testRuntimeOnly(mn.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The NATS integration test runs against a real server (Testcontainers has no NATS module: a plain container).
    testImplementation(mn.testcontainers.core)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.shadowJar {
    archiveBaseName.set("blackhole-discord")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}
