group = "net.onelitefeather.blackhole.client"
version = "unspecified"

dependencies {
    implementation(project(":api"))
    implementation(mn.micronaut.jackson.core)
    implementation(mn.micronaut.jackson.databind)
    compileOnly("org.jetbrains:annotations:26.0.1")

    testImplementation(project(":api"))
    testImplementation(mn.junit.jupiter.api)
    testImplementation("org.jetbrains:annotations:26.0.1")
    testImplementation(mn.micronaut.jackson.core)
    testImplementation(mn.micronaut.jackson.databind)
    testRuntimeOnly(mn.junit.jupiter.engine)
}
