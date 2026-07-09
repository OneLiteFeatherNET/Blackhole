// gradle.properties' `version` line carries a `# x-release-please-version` marker comment that
// release-please rewrites on every release. Java .properties syntax only treats `#` as a comment
// starter at the start of a line, so the raw value includes that trailing comment - strip it here.
allprojects {
    group = "net.onelitefeather"
    version = (version as String).substringBefore('#').trim()
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")

    tasks {
        getByName<JavaCompile>("compileJava") {
            // Matches the toolchain (JavaLanguageVersion 25) each module already builds with -
            // otis-client (a backend dependency) is published targeting Java 25 with no lower
            // --release cap, so a 21 target here made it an incompatible dependency variant.
            options.release.set(25)
            options.encoding = "UTF-8"
        }
        getByName<JacocoReport>("jacocoTestReport") {
            dependsOn(project.tasks.named("test"))
            reports {
                xml.required.set(true)
            }
        }
        getByName<Test>("test") {
            jvmArgs("-Dminestom.inside-test=true")
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
            }
        }
    }
}
