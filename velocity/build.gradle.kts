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

publishing {
    publications.create<MavenPublication>("maven") {
        artifact(tasks.shadowJar)
        version = rootProject.version as String
        artifactId = "blackhole-velocity"
        groupId = project.group as String
        pom {
            name = "Blackhole Velocity"
            description = "A Velocity proxy plugin that enforces punishments and feeds chat/session data to the Blackhole backend"
            url = "https://github.com/OneLiteFeatherNET/Blackhole"
            licenses {
                license {
                    name = "AGPL-3.0"
                    url = "https://www.gnu.org/licenses/agpl-3.0.en.html"
                }
            }
            developers {
                developer {
                    id = "themeinerlp"
                    name = "Phillipp Glanz"
                    email = "p.glanz@madfix.me"
                }
                developer {
                    id = "theEvilReaper"
                    name = "Steffen Wonning"
                    email = "steffenwx@gmail.com"
                }
            }
            scm {
                connection = "scm:git:git://github.com:OneLiteFeatherNET/Blackhole.git"
                developerConnection = "scm:git:ssh://git@github.com:OneLiteFeatherNET/Blackhole.git"
                url = "https://github.com/OneLiteFeatherNET/Blackhole"
            }
        }
    }

    repositories {
        maven {
            authentication {
                credentials(PasswordCredentials::class) {
                    // Those credentials need to be set under "Settings -> Secrets -> Actions" in your repository
                    username = System.getenv("ONELITEFEATHER_MAVEN_USERNAME")
                    password = System.getenv("ONELITEFEATHER_MAVEN_PASSWORD")
                }
            }

            name = "OneLiteFeatherRepository"
            val releasesRepoUrl = uri("https://repo.onelitefeather.dev/onelitefeather-releases")
            val snapshotsRepoUrl = uri("https://repo.onelitefeather.dev/onelitefeather-snapshots")
            url =
                if (version.toString().contains("SNAPSHOT") || version.toString().contains("BETA") || version.toString()
                        .contains("ALPHA")
                ) snapshotsRepoUrl else releasesRepoUrl
        }
    }
}
