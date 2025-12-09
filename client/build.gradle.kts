plugins {
    id("org.openapi.generator") version "7.17.0"
    `maven-publish`
}

val outDir = layout.buildDirectory.dir("generated/openapi")

dependencies {
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.core)
    implementation(libs.jackson.annotations)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.databind.nullable)
    implementation(libs.jakarta.annotation.api)
}

// OpenAPI Generator configuration
openApiGenerate {
    generatorName.set("java")
    library.set("native")
    inputSpec.set("$projectDir/specs/blackhole-api-0.0.1.yml")
    outputDir.set(outDir.get().asFile.absolutePath)
    apiPackage.set("net.onelitefeather.blackhole.client.api")
    invokerPackage.set("net.onelitefeather.blackhole.client.invoker")
    modelPackage.set("net.onelitefeather.blackhole.client.model")
    additionalProperties.set(
        mapOf(
            "dateLibrary" to "java8",
            "useTags" to "true",
            "interfaceOnly" to "true",
            "useSpringBoot3" to "false",
            "useSpring4Annotations" to "false",
            "useJakartaEe" to "true",
            "serializationLibrary" to "gson",
            "artifactId" to "blackhole-client",
            "groupId" to "net.onelitefeather.blackhole",
            "artifactVersion" to rootProject.version.toString(),
            "buildTool" to "gradle",
            "generateBuilders" to "true",

            "useJava8Time" to "true",
            "hideGenerationTimestamp" to "true"
        )
    )
    globalProperties.set(
        mapOf(
            "apiTests" to "false",
            "modelTests" to "false"
        )
    )
}

sourceSets.named("main") {
    java.srcDir(outDir.map { it.dir("src/main/java") })
}


java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withJavadocJar()
    withSourcesJar()
}

tasks.named("compileJava") {
    dependsOn(tasks.named("openApiGenerate"))
}

tasks.named("sourcesJar") {
    dependsOn(tasks.named("openApiGenerate"))
}

tasks {

    javadoc {
        options {
            (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet")
        }
    }
}