/*
 * Край Podbor: порты к Xray и миру, их реализации и актор, который исполняет дела ядра.
 * Всё, что умеет Xray, делает Xray; наше — генератор конфига, проба-блоб, линия, актор.
 * Мультиплатформенный: чистое — commonMain (JVM и iOS), сокеты, TLS, gRPC и процессы — jvmMain.
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project.dependencies.platform(libs.arrow.stack))
            implementation(libs.arrow.core)
            implementation(libs.arrow.fx.coroutines)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlincrypto.sha2)
        }
        jvmMain.dependencies {
            implementation(project(":xray-proto"))
            runtimeOnly(libs.grpc.netty)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotest.framework.datatest)
            implementation(libs.kotest.property)
            implementation(libs.kotlinx.coroutines.test)
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// `:driver:test` — прежнее имя для команд и CI; компиляция метаданных common ловит java.* в commonMain.
tasks.register("test") {
    dependsOn("jvmTest", "compileCommonMainKotlinMetadata")
}

// Classpath тестов для живых прогонов вне Gradle (в изолированной сети он не стартует — блокировки через loopback).
tasks.register("printTestClasspath") {
    val cp = kotlin.jvm().compilations["test"].runtimeDependencyFiles!! + kotlin.jvm().compilations["test"].output.allOutputs
    doLast { println(cp.asPath) }
}
