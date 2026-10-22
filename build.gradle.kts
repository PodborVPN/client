// Корень сборки Podbor: модули объявлены в settings.gradle.kts, плагины — в каталоге версий.
// Плагины Kotlin/Android подключены здесь один раз (apply false), чтобы все модули делили один загрузчик классов.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
}
