group = "net.onelitefeather.blackhole.client"

plugins {
    `maven-publish`
    alias(libs.plugins.shadow)
    alias(libs.plugins.publishdata)
}

dependencies {
    implementation(project(":api"))
    implementation(mn.jackson.core)
    implementation(mn.jackson.databind)
    implementation(mn.jackson.datatype.jsr310)
    implementation(mn.jackson.datatype.jdk8)

    testImplementation(project(":api"))
    testImplementation(mn.junit.jupiter.api)
    testImplementation(libs.jetbrains.annotations)
    testImplementation(mn.jackson.core)
    testImplementation(mn.jackson.databind)
    testImplementation(mn.jackson.datatype.jdk8)
    testRuntimeOnly(mn.junit.jupiter.engine)
}

publishData {
    addBuildData()
    useGitlabReposForProject("196", "https://gitlab.onelitefeather.dev/")
    publishTask("shadowJar")
}

publishing {
    publications.create<MavenPublication>("maven") {
        // configure the publication as defined previously.
        publishData.configurePublication(this)
        version = publishData.getVersion(false)
    }

    repositories {
        maven {
            credentials(HttpHeaderCredentials::class) {
                name = "Job-Token"
                value = System.getenv("CI_JOB_TOKEN")
            }
            authentication {
                create("header", HttpHeaderAuthentication::class)
            }


            name = "Gitlab"
            // Get the detected repository from the publishing data
            url = uri(publishData.getRepository())
        }
    }
}
