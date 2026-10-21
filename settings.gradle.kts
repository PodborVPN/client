pluginManagement {
    repositories {
        // google() — плагин Android (AGP); остальное — как раньше.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // foojay-resolver: автоскачивание нужного JDK для toolchain'а.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "podbor"
include("core")
include("driver")
include("app")

dependencyResolutionManagement {
    // Репозитории модулей объявлены централизованно (JVM-модули дублируют mavenCentral() — это допустимо).
    repositories {
        google()
        mavenCentral()
    }
}
