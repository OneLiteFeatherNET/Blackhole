plugins {
    jacoco
    `java-library`
    `maven-publish`
}

version = "0.5.3"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.jetbrains.annotations)
    testImplementation(mn.junit.jupiter.api)
    testRuntimeOnly(mn.junit.jupiter.engine)
}

tasks {
    java {
        compileJava {
            options.release.set(21)
            options.encoding = "UTF-8"
        }
    }

    jacocoTestReport {
        dependsOn(test)
        reports {
            xml.required.set(true)
        }
    }
    test {
        finalizedBy(jacocoTestReport)
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
