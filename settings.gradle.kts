rootProject.name = "Blackhole"
plugins {
    id("io.micronaut.platform.catalog") version "4.4.4"
}

dependencyResolutionManagement {
    if (System.getenv("CI") != null) {
        repositoriesMode = RepositoriesMode.PREFER_SETTINGS
        repositories {
            maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            maven("https://repo.htl-md.schule/repository/Gitlab-Runner/")
            maven("https://repo.papermc.io/repository/maven-public/")
            maven {
                val groupdId = 28 // Gitlab Group
                val ciApiv4Url = System.getenv("CI_API_V4_URL")
                url = uri("$ciApiv4Url/groups/$groupdId/-/packages/maven")
                name = "GitLab"
                credentials(HttpHeaderCredentials::class.java) {
                    name = "Job-Token"
                    value = System.getenv("CI_JOB_TOKEN")
                }
                authentication {
                    create<HttpHeaderAuthentication>("header")
                }
            }
        }
    } else {
        repositories {
            mavenCentral()
            maven("https://repo.papermc.io/repository/maven-public/")
            maven {
                val groupdId = 28 // Gitlab Group
                url = uri("https://gitlab.onelitefeather.dev/api/v4/groups/$groupdId/-/packages/maven")
                name = "GitLab"
                credentials(HttpHeaderCredentials::class.java) {
                    name = "Private-Token"
                    value = providers.gradleProperty("gitLabPrivateToken").get()
                }
                authentication {
                    create<HttpHeaderAuthentication>("header")
                }
            }
        }
    }
    versionCatalogs {
        create("libs") {
            version("micronaut", "4.4.4")
            version("velocity", "3.4.0-SNAPSHOT")
            version("cloud.commands", "2.0.0")

            library("velocity-api", "com.velocitypowered", "velocity-api").versionRef("velocity")
            library("cloud-velocity", "org.incendo", "cloud-velocity").version("2.0.0-beta.10")
            library("cloud-annotations", "org.incendo", "cloud-annotations").versionRef("cloud.commands")
            library("cloudExtras", "org.incendo", "cloud-minecraft-extras").version("2.0.0-beta.10")
            plugin("micronaut.application", "io.micronaut.application").versionRef("micronaut")
            plugin("micronaut.aot", "io.micronaut.aot").versionRef("micronaut")
        }
    }
}
include("backend")
include("api")
include("client")
include("velocity")
