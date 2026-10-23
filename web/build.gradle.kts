// The UI is built with npm; Gradle only drives it so `./gradlew build` covers it too.
// Node comes from the environment (CI: actions/setup-node), so download is disabled.
plugins {
    id("com.github.node-gradle.node") version "7.1.0"
}

node {
    version.set("24.11.0")
    download.set(false)
}

val npmCi by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    args.set(listOf("ci"))
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
}

val webBuild by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(npmCi)
    args.set(listOf("run", "build"))
}

val webTest by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(npmCi)
    args.set(listOf("run", "test"))
}

tasks.named("assemble") {
    dependsOn(webBuild)
}

tasks.named("check") {
    dependsOn(webTest)
}
