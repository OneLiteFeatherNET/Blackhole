group = "net.theevilreaper.kali"
plugins {
    id("xyz.jpenilla.run-velocity") version "2.3.1"
    `maven-publish`
    alias(libs.plugins.shadow)
    alias(libs.plugins.publishdata)
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
