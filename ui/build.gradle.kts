/*
 * Экраны Podbor на Compose — одни на телефон и браузер. Ресурсы лежат у Android, браузер читает те же файлы.
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    androidLibrary {
        namespace = "podbor.ui"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
    }
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(project(":screen"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(compose.material3)
            implementation(project.dependencies.platform(libs.arrow.stack))
            implementation(libs.arrow.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        wasmJsMain.dependencies {
            implementation(project(":ui-res"))
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose-stability.conf"))
}
