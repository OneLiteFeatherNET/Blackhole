rootProject.name = "Blackhole"
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://eldonexus.de/repository/maven-public/")
    }
}

plugins {
    id("io.micronaut.platform.catalog") version "4.6.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            name = "OneLiteFeatherRepository"
            url = uri("https://repo.onelitefeather.dev/onelitefeather")
            if (System.getenv("CI") != null) {
                credentials {
                    username = System.getenv("ONELITEFEATHER_MAVEN_USERNAME")
                    password = System.getenv("ONELITEFEATHER_MAVEN_PASSWORD")
                }
            } else {
                credentials(PasswordCredentials::class)
                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    versionCatalogs {
        create("libs") {
            version("micronaut", "4.6.0")
            version("velocity", "3.4.0-SNAPSHOT")
            version("cloud.commands", "2.0.0")
            version("publishdata", "1.4.0")
            version("shadow", "9.2.2")
            version("jetbrains.annotations", "26.0.2-1")

            library("jetbrains.annotations", "org.jetbrains", "annotations").versionRef("jetbrains.annotations")

            library("velocity-api", "com.velocitypowered", "velocity-api").versionRef("velocity")
            library("cloud-velocity", "org.incendo", "cloud-velocity").version("2.0.0-beta.11")
            library("cloud-annotations", "org.incendo", "cloud-annotations").versionRef("cloud.commands")
            library("cloudExtras", "org.incendo", "cloud-minecraft-extras").version("2.0.0-SNAPSHOT")

            plugin("micronaut.application", "io.micronaut.application").versionRef("micronaut")
            plugin("micronaut.aot", "io.micronaut.aot").versionRef("micronaut")
            plugin("publishdata", "de.chojo.publishdata").versionRef("publishdata")
            plugin("shadow", "com.gradleup.shadow").versionRef("shadow")
        }
    }
}
include("backend")
include("api")
include("client")
include("velocity")

include("phoca")