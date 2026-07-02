rootProject.name = "Blackhole"

plugins {
    id("io.micronaut.platform.catalog") version "5.0.1"
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
            version("micronaut", "5.0.2")
            version("velocity", "3.4.0")
            version("cloud.commands", "2.0.0")
            version("shadow", "9.4.3")
            version("jetbrains.annotations", "26.1.0")
            version("jackson", "2.22.0")
            version("jakarta-annotation", "3.0.0")
            version("openapi.generator", "7.23.0")

            library("jetbrains.annotations", "org.jetbrains", "annotations").versionRef("jetbrains.annotations")

            //Jackson
            library("jackson-bom",  "com.fasterxml.jackson", "jackson-bom").versionRef("jackson")
            library("jackson-core",        "com.fasterxml.jackson.core", "jackson-core").withoutVersion()
            library("jackson-annotations", "com.fasterxml.jackson.core", "jackson-annotations").withoutVersion()
            library("jackson-databind",    "com.fasterxml.jackson.core", "jackson-databind").withoutVersion()
            library("jackson-datatype-jsr310", "com.fasterxml.jackson.datatype", "jackson-datatype-jsr310").withoutVersion()

            library("jackson-databind-nullable", "org.openapitools", "jackson-databind-nullable").version("0.2.10")
            library("jakarta-annotation-api", "jakarta.annotation", "jakarta.annotation-api").versionRef("jakarta-annotation")

            library("velocity-api", "com.velocitypowered", "velocity-api").versionRef("velocity")
            library("cloud-velocity", "org.incendo", "cloud-velocity").version("2.0.0-SNAPSHOT")
            library("cloud-annotations", "org.incendo", "cloud-annotations").versionRef("cloud.commands")
            library("cloudExtras", "org.incendo", "cloud-minecraft-extras").version("2.0.0-SNAPSHOT")

            plugin("micronaut.application", "io.micronaut.application").versionRef("micronaut")
            plugin("micronaut.aot", "io.micronaut.aot").versionRef("micronaut")
            plugin("shadow", "com.gradleup.shadow").versionRef("shadow")
            plugin("openapi.generator", "org.openapi.generator").versionRef("openapi.generator")
        }
    }
}
include("backend")
include("client")
include("velocity")

include("phoca")