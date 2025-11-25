group = "net.onelitefeather.blackhole.api"

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(platform(mn.micronaut.core.bom))
    implementation(libs.jetbrains.annotations)
    implementation(project(":phoca"))

    testImplementation(project(":phoca"))
    testImplementation(mn.junit.jupiter.api)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(mn.junit.jupiter.engine)
}
