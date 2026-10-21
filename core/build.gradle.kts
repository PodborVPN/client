/*
 * Ядро Podbor: чистые машины Мили — подписка, сценарий сети, политика и бюджет перебора,
 * нажатие, объяснение-свёртка, экран. Ни Android, ни сети, ни часов: IO живёт в :driver.
 */

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.arrow.stack))
    implementation(libs.arrow.core)
    implementation(libs.arrow.core.serialization)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.framework.datatest)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// JDK 21 (LTS) скачивается foojay-resolver'ом, если в системе другой.
kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
