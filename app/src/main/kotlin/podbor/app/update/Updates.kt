package podbor.app.update

import arrow.core.Either
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * Обновлялка сборки с GitHub: последний релиз — из API GitHub, предложение — если там новее. Скачивает и ставит APK сам
 * Android (браузер): своего установщика нет. Здесь — только чистые функции; сеть — у Desk.
 */

/** Последний релиз на GitHub — JSON с тегом и ссылками. */
const val LATEST_RELEASE_API = "https://api.github.com/repos/PodborVPN/client/releases/latest"

/** Спрашивать GitHub не чаще: спрашиваем при каждом открытии экрана, а запрос в белых списках идёт туннелем. */
private const val CHECK_EVERY_MS = 3_600_000L

data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
}

private val NUMBER = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")

/** «v0.10.0», «0.8.0-dev» → номер; префикс v и суффикс сборки — не часть номера. Не X.Y.Z — null. */
fun versionOf(s: String): Version? =
    NUMBER.find(s.trim())?.destructured?.let { (a, b, c) -> Version(a.toInt(), b.toInt(), c.toInt()) }

/** Релиз: тег и куда вести человека — прямо на APK, а нет его в assets — на страницу релиза. */
data class Release(val tag: String, val url: String)

/** Что предложить на экране: версию и ссылку. */
data class Offer(val version: String, val url: String)

private val json = Json { ignoreUnknownKeys = true }

/** Ответ API GitHub → релиз; без тега, битый или ссылка не https — null (тихо: обновлялка не главное). */
fun releaseOf(body: String): Release? {
    val o = Either.catch { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val tag = o.str("tag_name") ?: return null
    val apk = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .firstOrNull { it.str("name")?.endsWith(".apk") == true }?.str("browser_download_url")
    val url = (apk ?: o.str("html_url"))?.takeIf { it.startsWith("https://") } ?: return null
    return Release(tag, url)
}

/** Предложение, только если на GitHub версия новее текущей [current]; номер не разобрать — молчим. */
fun offerOf(current: String, latest: Release?): Offer? {
    val have = versionOf(current) ?: return null
    val got = latest?.let { versionOf(it.tag) } ?: return null
    return if (got > have) Offer("${got.major}.${got.minor}.${got.patch}", latest.url) else null
}

/** Пора спросить GitHub: ни разу не спрашивали, прошло [CHECK_EVERY_MS] или часы ушли назад. */
fun checkDue(lastMs: Long?, nowMs: Long): Boolean =
    lastMs == null || nowMs < lastMs || nowMs - lastMs >= CHECK_EVERY_MS

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
