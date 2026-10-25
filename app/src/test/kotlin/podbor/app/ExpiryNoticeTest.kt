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
import podbor.app.vpn.EXTRA_CHOOSE_SUBSCRIPTION
import podbor.app.vpn.EXTRA_RENEW_URL
import podbor.app.vpn.dismissExpiry
import podbor.app.sub.Shelf
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
        store.saveOne(Subscription("https://a.b/s", "vless://a", provider = ProviderInfo(title = "Мой VPN", expireS = expireS, support = podbor.screen.Support.Link("https://t.me/help"))))
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

    /** Подписка, что кончается через два дня; [others] — сколько ещё подписок в списке. */
    private fun expiring(others: Int): SubscriptionStore = runBlocking {
        val now = System.currentTimeMillis()
        val store = SubscriptionStore(Files.createTempDirectory("expiry").toFile())
        val current = Subscription("https://a.b/s", "vless://a", id = "A", provider = ProviderInfo(expireS = now / 1000 + 2 * 86_400 + 3_600))
        store.save(Shelf(listOf(current) + List(others) { Subscription(null, "vless://$it", id = "o$it") }, currentId = "A"))
        store
    }

    @Test
    fun withOtherSubscriptionsOffersRenewAndChange() = runBlocking {
        ExpiryNotice(app, expiring(others = 1)) { System.currentTimeMillis() }.check()
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(listOf("Продлить", "Сменить"), n.actions.map { it.title.toString() })
        val change = shadowOf(n.actions[1].actionIntent).savedIntent
        assertEquals(true, change.getBooleanExtra(EXTRA_CHOOSE_SUBSCRIPTION, false))
    }

    /** Кнопки ведут в Podbor (а не сразу в браузер): только так уведомление снимается и по кнопке. */
    @Test
    fun renewGoesThroughPodborWithSupportLink() = runBlocking {
        val now = System.currentTimeMillis()
        val store = SubscriptionStore(Files.createTempDirectory("expiry").toFile())
        store.saveOne(Subscription("https://a.b/s", "vless://a", provider = ProviderInfo(expireS = now / 1000 + 2 * 86_400 + 3_600, support = podbor.screen.Support.Link("https://t.me/help"))))
        ExpiryNotice(app, store) { now }.check()
        val renew = shadowOf(shadowOf(nm).allNotifications.single().actions[0].actionIntent).savedIntent
        assertEquals("podbor.app.ui.MainActivity", renew.component?.className)
        assertEquals("https://t.me/help", renew.getStringExtra(EXTRA_RENEW_URL))
    }

    @Test
    fun dismissRemovesExpiryNotice() = runBlocking {
        ExpiryNotice(app, expiring(others = 1)) { System.currentTimeMillis() }.check()
        assertEquals(1, shadowOf(nm).allNotifications.size)
        dismissExpiry(app)
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun singleSubscriptionOffersOnlyRenew() = runBlocking {
        ExpiryNotice(app, expiring(others = 0)) { System.currentTimeMillis() }.check()
        assertEquals(listOf("Продлить"), shadowOf(nm).allNotifications.single().actions.map { it.title.toString() })
    }

    @Test
    fun noExpiryNoNotice() = runBlocking {
        val store = SubscriptionStore(Files.createTempDirectory("expiry").toFile())
        store.saveOne(Subscription("https://a.b/s", "vless://a", provider = ProviderInfo(expireS = 0)))
        ExpiryNotice(app, store) { System.currentTimeMillis() }.check()
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }
}
