group = "net.theevilreaper.kali"
version = "unspecified"

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
