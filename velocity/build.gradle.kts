group = "net.onelitefeather.blackhole.velocity"

plugins {
    id("xyz.jpenilla.run-velocity") version "3.0.2"
    `maven-publish`
    alias(libs.plugins.shadow)
}

dependencies {
    annotationProcessor(libs.velocity.api)

    implementation(project(":client"))
    implementation(project(":phoca"))

    implementation(libs.cloud.annotations)
    implementation(libs.cloud.velocity)
    implementation(libs.lettuce.core)

    // client's jackson-databind is `implementation`-scoped there, so it isn't exposed
    // transitively - needed here directly to (de)serialize RedisSyncService's wire messages.
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)

    compileOnly(libs.velocity.api)

    testImplementation(mn.junit.jupiter.api)
    testRuntimeOnly(mn.junit.jupiter.engine)
}

tasks {
    runVelocity {
        // Configure the Velocity version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        velocityVersion("3.3.0-SNAPSHOT")
    }
}
