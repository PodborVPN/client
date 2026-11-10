/*
 * Мозг Podbor без платформы: подписки, сессия, порядок действий и файловые хранилища. Платформа (Android, Linux)
 * подставляет сеть, движок Xray и способ показать состояние.
 */

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    api(project(":driver"))
    api(project(":screen"))
    api(project.dependencies.platform(libs.arrow.stack))
    api(libs.arrow.core)
    api(libs.arrow.fx.coroutines)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
}
