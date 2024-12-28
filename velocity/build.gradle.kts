group = "net.theevilreaper.kali"
plugins {
    // Apply the plugin
    id("xyz.jpenilla.run-velocity") version "2.3.1"
    id("com.gradleup.shadow") version "9.0.0-beta4"
}


dependencies {
    annotationProcessor(libs.velocity.api)

    implementation(project(":api"))
    implementation(project(":client"))

    implementation(libs.cloud.annotations)
    implementation(libs.cloud.velocity)

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
