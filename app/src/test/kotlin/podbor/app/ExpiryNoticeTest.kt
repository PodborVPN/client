package podbor.app

import android.app.NotificationManager
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import podbor.app.sub.ProviderInfo
import podbor.app.sub.Subscription
import podbor.app.sub.SubscriptionStore
import podbor.app.vpn.ExpiryNotice
import java.nio.file.Files

/** Уведомление о конце срока: один раз на день срока, тап — поддержка провайдера. Robolectric, без телефона. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExpiryNoticeTest {

    private val app = RuntimeEnvironment.getApplication()
    private val nm = app.getSystemService(NotificationManager::class.java)

    @Test
    fun remindsOncePerDayAndLeadsToSupport() = runBlocking {
        val now = System.currentTimeMillis()
        val store = SubscriptionStore(Files.createTempDirectory("expiry").toFile())
        val expireS = now / 1000 + 2 * 86_400 + 3_600
        store.save(Subscription("https://a.b/s", "vless://a", provider = ProviderInfo(title = "Мой VPN", expireS = expireS, supportUrl = "https://t.me/help")))
        val notice = ExpiryNotice(app, store) { now }
        notice.check()
        val posted = shadowOf(nm).allNotifications
        assertEquals(1, posted.size)
        val n = shadowOf(posted.single())
        assertEquals("Мой VPN — продли у провайдера", n.contentText)
        val tap = shadowOf(posted.single().contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, tap.action)
        assertEquals("https://t.me/help", tap.dataString)
        nm.cancelAll()
        notice.check()
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun noExpiryNoNotice() = runBlocking {
        val store = SubscriptionStore(Files.createTempDirectory("expiry").toFile())
        store.save(Subscription("https://a.b/s", "vless://a", provider = ProviderInfo(expireS = 0)))
        ExpiryNotice(app, store) { System.currentTimeMillis() }.check()
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }
}
