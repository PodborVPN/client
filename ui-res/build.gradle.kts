/*
 * Значки и шрифт для браузера: те же файлы, что у Android, упакованные как ресурсы Compose. Отдельным модулем — чтобы
 * библиотека ресурсов Compose не попадала в приложение для телефона.
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.components.resources)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "podbor.app.ui.res"
    customDirectory("commonMain", provider { rootProject.layout.projectDirectory.dir("ui/src/androidMain/res") })
}
