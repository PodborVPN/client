package podbor.driver

import arrow.core.Either
import com.google.protobuf.CodedOutputStream
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import arrow.fx.coroutines.resourceScope
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

/** geoip.dat в формате Xray: GeoIPList{ entry=1: GeoIP{ country_code=1, cidr=2: CIDR{ ip=1, prefix=2 }, reverse_match=3 } }. */
internal fun geoipBytes(vararg entries: Triple<String, List<Pair<String, Int>>, Boolean>): ByteArray {
    val out = ByteArrayOutputStream()
    val list = CodedOutputStream.newInstance(out)
    for ((code, cidrs, reverse) in entries) {
        val geo = ByteArrayOutputStream()
        val g = CodedOutputStream.newInstance(geo)
        g.writeString(1, code)
        for ((ip, prefix) in cidrs) {
            val cidr = ByteArrayOutputStream()
            val c = CodedOutputStream.newInstance(cidr)
            c.writeByteArray(1, InetAddress.getByName(ip).address)
            c.writeUInt32(2, prefix)
            c.flush()
            g.writeByteArray(2, cidr.toByteArray())
        }
        if (reverse) g.writeBool(3, true)
        g.flush()
        list.writeByteArray(1, geo.toByteArray())
    }
    list.flush()
    return out.toByteArray()
}

private fun load(bytes: ByteArray): Either<EngineError, GeoCountry> {
    val f = Files.createTempFile("geoip", ".dat").toFile().apply { deleteOnExit(); writeBytes(bytes) }
    return GeoCountry.load(f)
}

class GeoCountryTest : FunSpec({
    val sample = geoipBytes(
        Triple("RU", listOf("10.8.0.0" to 16, "2a00:1450::" to 32), false),
        Triple("NL", listOf("10.8.1.0" to 24, "172.16.0.0" to 12, "2001:db8::" to 32), false),
        Triple("XX", listOf("192.168.9.0" to 24), true),
    )

    test("v4 и v6 находят свою страну, самый длинный префикс побеждает") {
        val geo = load(sample).shouldBeInstanceOf<Either.Right<GeoCountry>>().value
        geo.of("10.8.3.4") shouldBe "RU"
        geo.of("10.8.1.77") shouldBe "NL"
        geo.of("172.20.1.1") shouldBe "NL"
        geo.of("2a00:1450:4001::1") shouldBe "RU"
        geo.of("2001:db8:1::5") shouldBe "NL"
    }

    test("вне диапазонов, не IP и reverse_match — null") {
        val geo = load(sample).shouldBeInstanceOf<Either.Right<GeoCountry>>().value
        geo.of("8.8.8.8") shouldBe null
        geo.of("2606:4700::1") shouldBe null
        geo.of("192.168.9.9") shouldBe null
        geo.of("example.com") shouldBe null
    }

    test("повреждённый, обрезанный, пустой и отсутствующий файл — Left") {
        load(sample.copyOf(sample.size / 2 + 3)).shouldBeInstanceOf<Either.Left<EngineError>>()
        load(ByteArray(40) { 0xFF.toByte() }).shouldBeInstanceOf<Either.Left<EngineError>>()
        load(ByteArray(0)).shouldBeInstanceOf<Either.Left<EngineError>>()
        GeoCountry.load(File("/нет/такого/geoip.dat")).shouldBeInstanceOf<Either.Left<EngineError>>()
    }

    test("Driver.warmUp: вход вне ru получает страну из geoip, без неё — ?") {
        val w = World(ips = listOf("10.8.3.4", "8.8.8.8"), ruIps = emptySet(), line = line(podbor.core.Reach.Up, podbor.core.Reach.Up), fitsPorts = emptySet(),
            countries = mapOf("10.8.3.4" to "NL"))
        val servers = runBlocking {
            resourceScope {
                with(w.driver()) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value.servers
            }
        }
        servers.map { it.entry } shouldBe listOf(podbor.core.Entry.Foreign("NL"), podbor.core.Entry.Foreign("?"))
    }

    test("настоящий geoip.dat (если собран): время загрузки и занятая память, известные адреса") {
        val real = File("../app/build/generated/xray-assets/xray/geoip.dat")
        if (!real.isFile) return@test
        fun used(): Long { repeat(3) { System.gc() }; return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } }
        val before = used()
        val t0 = System.nanoTime()
        val geo = GeoCountry.load(real).shouldBeInstanceOf<Either.Right<GeoCountry>>().value
        val ms = (System.nanoTime() - t0) / 1_000_000
        val retained = used() - before
        println("geoip.dat: ${real.length() / 1024} KiB, загрузка $ms мс, удержано ~${retained / 1024} KiB, диапазонов ${geo.ranges}")
        geo.of("8.8.8.8") shouldBe "GOOGLE" // вложенный более узкий диапазон побеждает
        geo.of("77.88.8.8") shouldBe "RU"
        geo.of("2001:4860:4860::8888") shouldBe "US"
    }
})
