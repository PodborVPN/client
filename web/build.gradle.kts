/*
 * Веб-морда Linux-клиента: те же экраны и тот же ViewModel, что на телефоне; мозг — в демоне, за WebSocket.
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    wasmJs {
        browser { commonWebpackConfig { outputFileName = "podbor.js" } }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":ui"))
            implementation(project(":remote"))
            implementation(project.dependencies.platform(libs.arrow.stack))
            implementation(libs.arrow.core)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
