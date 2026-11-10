/*
 * Podbor для Linux: мозг в процессе, Xray — отдельным бинарём, наружу — прокси и веб-экран. Без туннеля на всю систему.
 */

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("podbor.linux.MainKt")
    applicationName = "podbor-linux"
}

dependencies {
    implementation(project(":host"))
    implementation(project(":xray-proto"))
    implementation(libs.grpc.netty)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Контекст образа: собранное приложение и закреплённые geo-файлы; сам образ сборку не повторяет.
tasks.register<Sync>("dockerContext") {
    dependsOn(":app:prepareXrayAssets")
    into(layout.buildDirectory.dir("docker"))
    from(layout.projectDirectory.dir("docker"))
    from(tasks.named("installDist")) { into("podbor-linux") }
    from(project(":app").layout.buildDirectory.dir("generated/xray-assets")) { into("assets") }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
