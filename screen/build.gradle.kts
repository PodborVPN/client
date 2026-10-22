/*
 * Экран Podbor без вьюх: ViewModel, связь с VPN, вид, обновлялка — общие для Android и iOS. Вьюхи — на платформе
 * (Compose в :app, SwiftUI в ios/). Только common-код, без java.* и android.*.
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    androidLibrary {
        namespace = "podbor.screen"
        compileSdk = 37
        minSdk = 26
    }
    // JVM — для быстрых тестов (kotest), как у ядра.
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.androidx.lifecycle.viewmodel)
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
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// `:screen:test` — как у ядра: JVM-тесты и компиляция метаданных common (ловит java.* до iOS).
tasks.register("test") {
    dependsOn("jvmTest", "compileCommonMainKotlinMetadata")
}
