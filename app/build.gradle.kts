/*
 * Android-клиент Podbor: Compose-экран, VpnService, libXray. Ядро и край — обычные JVM-библиотеки.
 * Geo-файлы Xray и списки белых IP/SNI попадают в assets как сгенерированный ресурс сборки (в git не едут).
 */

import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    google()
    mavenCentral()
}

/** Скачивает релиз Xray (кэш вне репо), сверяет sha256, кладёт geo-файлы и списки белых в каталог assets. */
abstract class PrepareXrayAssets : DefaultTask() {
    @get:Input
    abstract val zipUrl: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val shaFile: RegularFileProperty

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:Input
    abstract val whitelistBaseUrl: Property<String>

    /** Строки «sha256  имя-файла» для каждого списка (формат sha256sum). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val whitelistShaFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val expected = shaFile.get().asFile.readText().trim().substringBefore(' ').lowercase()
        val zip = cacheDir.get().asFile.resolve("Xray-android-arm64-v8a.zip")
        if (!zip.isFile || sha256(zip) != expected) {
            zip.parentFile.mkdirs()
            download(zipUrl.get(), zip)
        }
        val actual = sha256(zip)
        if (actual != expected) {
            zip.delete()
            throw GradleException("sha256 архива Xray не совпал: ожидали $expected, получили $actual")
        }

        val out = outputDir.get().asFile
        out.deleteRecursively()
        val xrayDir = out.resolve("xray").also { it.mkdirs() }
        ZipFile(zip).use { z ->
            for (name in listOf("geoip.dat", "geosite.dat")) {
                val entry = z.getEntry(name) ?: throw GradleException("в архиве Xray нет $name")
                z.getInputStream(entry).use { i -> xrayDir.resolve(name).outputStream().use { i.copyTo(it) } }
            }
        }

        val pins = whitelistShaFile.get().asFile.readLines().filter { it.isNotBlank() }
            .associate { it.trim().substringAfter(' ').trim() to it.trim().substringBefore(' ').lowercase() }
        val wlDir = out.resolve("whitelist").also { it.mkdirs() }
        for ((remote, local) in listOf("ru-mobile-whitelist-ip.txt" to "ip.txt", "ru-mobile-whitelist-sni.txt" to "sni.txt")) {
            val pin = pins[remote] ?: throw GradleException("в whitelist.sha256 нет строки для $remote")
            val file = cacheDir.get().asFile.resolve(remote)
            if (!file.isFile || sha256(file) != pin) {
                file.parentFile.mkdirs()
                download("${whitelistBaseUrl.get()}/$remote", file)
            }
            val got = sha256(file)
            if (got != pin) {
                file.delete()
                throw GradleException("sha256 списка $remote не совпал: ожидали $pin, получили $got")
            }
            file.copyTo(wlDir.resolve(local), overwrite = true)
        }
    }

    private fun download(url: String, to: File) {
        val conn = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        conn.getInputStream().use { input -> to.outputStream().use { input.copyTo(it) } }
    }

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}

val prepareXrayAssets = tasks.register<PrepareXrayAssets>("prepareXrayAssets") {
    zipUrl.set("https://github.com/XTLS/Xray-core/releases/download/v26.10.17/Xray-android-arm64-v8a.zip")
    shaFile.set(layout.projectDirectory.file("geo.sha256"))
    cacheDir.set(File(System.getProperty("user.home"), ".cache/podbor"))
    whitelistBaseUrl.set("https://github.com/ancored/ru-mobile-whitelist/releases/download/2026.10.19-10")
    whitelistShaFile.set(layout.projectDirectory.file("whitelist.sha256"))
    outputDir.set(layout.buildDirectory.dir("generated/xray-assets"))
}

/** Версия — из `version.txt` (его ведёт release-please): X.Y.Z → versionName, versionCode = X·10000 + Y·100 + Z. */
val appVersion: Pair<String, Int> = rootProject.file("version.txt").readText().trim().let { v ->
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(v)
        ?: error("version.txt: ожидается X.Y.Z, получено «$v»")
    val (major, minor, patch) = m.destructured.toList().map { it.toInt() }
    check(minor < 100 && patch < 100) { "version.txt: minor и patch — до 99, получено «$v»" }
    v to major * 10_000 + minor * 100 + patch
}

android {
    namespace = "podbor.app"
    compileSdkVersion("android-37.0")
    defaultConfig {
        applicationId = "podbor.app"
        minSdk = 26
        targetSdk = 37
        versionName = appVersion.first
        versionCode = appVersion.second
        // Цель — arm64; libXray собран только под него.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    // Не-отладочная сборка тем же debug-ключом (без релизной обвязки): Compose в debuggable-сборке в разы
    // медленнее, производительность меряется и раздаётся только так.
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    packaging {
        // Дубликаты метаданных из gRPC/protobuf/Arrow.
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES")
    }
    // Юнит-тесты :app — kotest на JUnit Platform, как в :core/:driver.
    testOptions.unitTests.all { it.useJUnitPlatform() }
    // Compose UI-тесты на Robolectric (JUnit 4 через vintage) читают ресурсы приложения: шрифты, иконки.
    testOptions.unitTests.isIncludeAndroidResources = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Сгенерированный каталог — источник assets каждого варианта (зависимость задач проводится автоматически).
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareXrayAssets, PrepareXrayAssets::outputDir)
    }
}

kotlin {
    jvmToolchain(21)
}

// Карточки анализа — значения ядра: без этого строгий пропуск сравнивает их по ссылке и перерисовывает каждую на доску.
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    implementation(project(":core"))
    // На Android netty-shaded не нужен — транспорт gRPC даёт okhttp.
    implementation(project(":driver")) { exclude(group = "io.grpc", module = "grpc-netty-shaded") }
    implementation(libs.grpc.okhttp)
    implementation(files("libs/libXray.aar"))
    implementation(platform(libs.arrow.stack))
    implementation(libs.arrow.core)
    implementation(libs.arrow.fx.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    // FileProvider для «поделиться Лентой» (та же версия, что приходит через activity).
    implementation(libs.androidx.core)
    // Сбор потоков экрана только в STARTED (collectAsStateWithLifecycle).
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Страницы — фрагменты с Compose внутри: сеть сверху, под ней подписка/анализ или главный.
    implementation(libs.androidx.fragment.ktx)
    // Сканер QR подписки без Google Play Services.
    implementation(libs.zxing.embedded)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.framework.datatest)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
