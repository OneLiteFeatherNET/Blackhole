group = "net.onelitefeather.blackhole.client"
version = "1.0.0"

dependencies {
    implementation(project(":api"))
    implementation(mn.jackson.core)
    implementation(mn.jackson.databind)
    implementation(mn.jackson.datatype.jsr310)
    implementation(mn.jackson.datatype.jdk8)

    compileOnly(libs.jetbrains.annotations)

    testImplementation(project(":api"))
    testImplementation(mn.junit.jupiter.api)
    testImplementation(libs.jetbrains.annotations)
    testImplementation(mn.jackson.core)
    testImplementation(mn.jackson.databind)
    testImplementation(mn.jackson.datatype.jdk8)
    testRuntimeOnly(mn.junit.jupiter.engine)
}
