plugins {
    `java-library`
}

// The one place the chat Markdown subset is defined (openspec change add-appeal-chat, decision 1).
// Plain Java with no runtime dependencies: the backend parses on read, the Discord bot maps the same tree.
// Jackson is on the test classpath only, to read the conformance and hostile corpora.
dependencies {
    testImplementation(platform(libs.jackson.bom))
    testImplementation(libs.jackson.databind)
    testImplementation(mn.junit.jupiter.api)
    testRuntimeOnly(mn.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
