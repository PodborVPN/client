/*
 * Android-клиент Podbor: Compose-экран, VpnService, libXray. Ядро и край — обычные JVM-библиотеки.
 * Geo-файлы Xray и списки белых IP/SNI попадают в assets как сгенерированный ресурс сборки (в git не едут).
 */

import java.net.URI
import java.security.MessageDigest

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

/** Скачивает geo-файлы Xray и списки белых (кэш вне репо), сверяет sha256 с закреплёнными, кладёт в каталог assets. */
abstract class PrepareXrayAssets : DefaultTask() {
    @get:Input
    abstract val geoBaseUrl: Property<String>

    /** Строки «sha256  имя-файла» для geoip.dat и geosite.dat (формат sha256sum). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val geoShaFile: RegularFileProperty

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
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val xrayDir = out.resolve("xray").also { it.mkdirs() }
        for (name in listOf("geoip.dat", "geosite.dat")) {
            fetchPinned(geoBaseUrl.get(), geoShaFile.get().asFile, name).copyTo(xrayDir.resolve(name))
        }
        val wlDir = out.resolve("whitelist").also { it.mkdirs() }
        for ((remote, local) in listOf("ru-mobile-whitelist-ip.txt" to "ip.txt", "ru-mobile-whitelist-sni.txt" to "sni.txt")) {
            fetchPinned(whitelistBaseUrl.get(), whitelistShaFile.get().asFile, remote).copyTo(wlDir.resolve(local))
        }
    }

    /** Файл [remote] из кэша, если sha совпал с закреплённым в [pins]; иначе скачивает `base/remote` и сверяет. */
    private fun fetchPinned(base: String, pins: File, remote: String): File {
        val pin = pins.readLines().filter { it.isNotBlank() }
            .associate { it.trim().substringAfter(' ').trim() to it.trim().substringBefore(' ').lowercase() }[remote]
            ?: throw GradleException("в ${pins.name} нет строки для $remote")
        val file = cacheDir.get().asFile.resolve(remote)
        if (!file.isFile || sha256(file) != pin) {
            file.parentFile.mkdirs()
            download("$base/$remote", file)
        }
        val got = sha256(file)
        if (got != pin) {
            file.delete()
            throw GradleException("sha256 $remote не совпал: ожидали $pin, получили $got")
        }
        return file
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
    geoBaseUrl.set("https://github.com/Loyalsoldier/v2ray-rules-dat/releases/download/202610202206")
    geoShaFile.set(layout.projectDirectory.file("geo.sha256"))
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

/** Переменные релизной подписи: все — подписываем ими, ни одной — null (debug-ключ), часть — ошибка сборки. */
fun releaseSigning(): Map<String, String>? {
    val names = listOf("SIGNING_KEYSTORE_PATH", "SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD")
    val set = names.associateWith { System.getenv(it) }.filterValues { !it.isNullOrEmpty() }.mapValues { it.value!! }
    return when (set.size) {
        0 -> null
        names.size -> set
        else -> error("релизная подпись: не заданы ${(names - set.keys).joinToString()}")
    }
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
        manifestPlaceholders["appLabel"] = "Podbor"
        // Цель — arm64; libXray собран только под него.
        ndk { abiFilters += "arm64-v8a" }
    }
    // buildConfig — версия на экране (BuildConfig.VERSION_NAME из version.txt).
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Релизный ключ — из переменных окружения (выпуск в CI); их нет — тот же debug-ключ: не-отладочная сборка
    // нужна и локально, Compose в debuggable-сборке в разы медленнее.
    val signing = releaseSigning()
    if (signing != null) {
        signingConfigs.create("release") {
            storeFile = file(signing.getValue("SIGNING_KEYSTORE_PATH"))
            storePassword = signing.getValue("SIGNING_STORE_PASSWORD")
            keyAlias = signing.getValue("SIGNING_KEY_ALIAS")
            keyPassword = signing.getValue("SIGNING_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (signing != null) "release" else "debug")
            isMinifyEnabled = false
            manifestPlaceholders["appLabel"] = "Podbor"
        }
        // Своя сборка для телефона разработчика: как release (не отладочная), но рядом с ним — свой пакет и
        // debug-ключ, релизный ключ не нужен. Ставится `./gradlew :app:installDev`.
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            manifestPlaceholders["appLabel"] = "Podbor dev"
        }
    }
    // Android Lint в CI: сборку валят только ошибки (API выше minSdk, манифест, разрешения); предупреждения — в отчёт.
    // Проверки свежести зависимостей ходят в сеть и краснели бы без правки кода — выключены.
    lint {
        abortOnError = true
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
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
    // UdsChannelBuilder: API Xray на Unix-сокете в личной папке, а не на порту loopback.
    implementation(libs.grpc.android)
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
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
