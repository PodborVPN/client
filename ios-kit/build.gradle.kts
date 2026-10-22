/*
 * PodborKit.xcframework — общий Kotlin-код одной статической библиотекой для iOS-приложения: ядро, край, экран.
 * Своего кода нет, только сборка: :ios-kit:assemblePodborKitReleaseXCFramework.
 */

import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    val podborKit = XCFramework("PodborKit")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "PodborKit"
            isStatic = true
            export(project(":core"))
            export(project(":driver"))
            export(project(":screen"))
            podborKit.add(this)
        }
    }

    sourceSets {
        iosMain.dependencies {
            api(project(":core"))
            api(project(":driver"))
            api(project(":screen"))
        }
    }
}
