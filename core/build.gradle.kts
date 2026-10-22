/*
 * Ядро Podbor: чистые машины Мили — подписка, сценарий сети, политика и бюджет перебора,
 * нажатие, объяснение-свёртка, экран. Ни Android, ни сети, ни часов: IO живёт в :driver.
 * Мультиплатформенное: JVM (Android, driver) и iOS — только common-код, без java.*.
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

// JDK 21 (LTS) скачивается foojay-resolver'ом, если в системе другой.
kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform(libs.arrow.stack))
            implementation(libs.arrow.core)
            implementation(libs.arrow.core.serialization)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlincrypto.sha2)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotest.property)
            implementation(libs.kotest.framework.datatest)
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

// `:core:test` — прежнее имя для команд и CI. JVM-компиляция пропускает java.* в commonMain,
// компиляция метаданных common — нет: так ядро не теряет iOS незаметно.
tasks.register("test") {
    dependsOn("jvmTest", "compileCommonMainKotlinMetadata")
}
