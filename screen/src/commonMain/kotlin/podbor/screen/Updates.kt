package podbor.screen

import kotlinx.serialization.Serializable

/*
 * Обновлялка сборки с GitHub: последний релиз — по веб-редиректу `releases/latest` → `releases/tag/<тег>` (это не API:
 * лимита 60 запросов в час на IP нет, ответ — пустой 302), предложение — если там новее. Скачивает и ставит APK сам
 * Android (браузер): своего установщика нет. Здесь — только чистые функции; сеть — у Desk.
 */

private const val REPO = "https://github.com/PodborVPN/client"

/** Страница последнего релиза: GitHub отвечает редиректом на страницу его тега. */
const val LATEST_RELEASE_PAGE = "$REPO/releases/latest"

data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
}

private val NUMBER = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")

/** «v0.10.0», «0.8.0-dev» → номер; префикс v и суффикс сборки — не часть номера. Не X.Y.Z — null. */
fun versionOf(s: String): Version? = when (val m = NUMBER.find(s.trim())) {
    null -> null
    else -> m.destructured.let { (a, b, c) -> Version(a.toInt(), b.toInt(), c.toInt()) }
}

/** Релиз: тег и куда вести человека — прямо на APK, а нет его ещё в релизе — на страницу релиза. */
@Serializable
data class Release(val tag: String, val url: String)

/** Что предложить на экране: версию и ссылку. */
@Serializable
data class Offer(val version: String, val url: String)

private val TAG_PAGE = Regex("""^${Regex.escape(REPO)}/releases/tag/([^/?#]+)$""")

/** Тег из Location редиректа [LATEST_RELEASE_PAGE]; не страница тега нашего репо (релизов нет) или не X.Y.Z — null. */
fun tagOf(location: String): String? = when (val m = TAG_PAGE.find(location.trim())) {
    null -> null
    else -> m.groupValues[1].takeIf { versionOf(it) != null }
}

/** APK релиза [tag] — по правилу имён сборки: `podbor-<X.Y.Z>.apk`. */
fun apkUrlOf(tag: String): String = "$REPO/releases/download/$tag/podbor-${tag.removePrefix("v")}.apk"

fun releasePageOf(tag: String): String = "$REPO/releases/tag/$tag"

/** Предложение, только если на GitHub версия новее текущей [current]; номер не разобрать — молчим. */
fun offerOf(current: String, latest: Release?): Offer? {
    val have = versionOf(current)
    if (have == null || latest == null) return null
    val got = versionOf(latest.tag)
    return if (got != null && got > have) Offer("${got.major}.${got.minor}.${got.patch}", latest.url) else null
}
