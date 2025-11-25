group = "net.onelitefeather.blackhole.client"

plugins {
    `maven-publish`
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(platform(mn.micronaut.core.bom))
    implementation(project(":api"))
    implementation(project(":phoca"))
    implementation(mn.jackson.core)
    implementation(mn.jackson.databind)
    implementation(mn.jackson.datatype.jsr310)
    implementation(mn.jackson.datatype.jdk8)
    implementation(libs.jetbrains.annotations)

    testImplementation(project(":api"))
    testImplementation(project(":phoca"))
    testImplementation(mn.junit.jupiter.api)
    testImplementation(libs.jetbrains.annotations)
    testImplementation(mn.jackson.core)
    testImplementation(mn.jackson.databind)
    testImplementation(mn.jackson.datatype.jdk8)
    testRuntimeOnly(mn.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
