group = "net.onelitefeather.blackhole.client"
version = "unspecified"

dependencies {
    implementation(project(":api"))
    implementation(mn.jackson.core)
    implementation(mn.jackson.databind)
    implementation(mn.jackson.datatype.jsr310)
    implementation(mn.jackson.datatype.jdk8)
    compileOnly("org.jetbrains:annotations:26.0.1")

    testImplementation(project(":api"))
    testImplementation(mn.junit.jupiter.api)
    testImplementation("org.jetbrains:annotations:26.0.1")
    testImplementation(mn.jackson.core)
    testImplementation(mn.jackson.databind)
    testImplementation(mn.jackson.datatype.jdk8)
    testRuntimeOnly(mn.junit.jupiter.engine)
}
