/*
 * Экран и мозг в разных процессах: браузер видит ServicePort, настоящий мозг живёт за WebSocket. Значения — снимками.
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    jvm()
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(project(":screen"))
            implementation(project.dependencies.platform(libs.arrow.stack))
            implementation(libs.arrow.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
