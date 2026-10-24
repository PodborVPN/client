package podbor.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import podbor.app.sub.Reminded
import podbor.app.sub.SubscriptionStore
import podbor.app.sub.expiryWords
import podbor.app.sub.reminderDay
import podbor.app.ui.MainActivity
import podbor.screen.Support
import java.time.ZoneId

private const val CHANNEL = "subscription"
private const val NOTIFICATION_ID = 2
private const val EXPIRE_S = "expire_s"
private const val DAY = "day"

/**
 * Напоминание о конце срока подписки уведомлением ([reminderDay]: за 3, 2, 1 и 0 дней, по разу на день срока). Тап —
 * поддержка провайдера, если он её дал, иначе экран Podbor. Что уже напомнили — в своих SharedPreferences. Разрешения на
 * уведомления нет — система молча не покажет: напоминание не главное.
 */
class ExpiryNotice(
    private val context: Context,
    private val subscriptions: SubscriptionStore,
    private val nowMs: () -> Long,
) {
    private val prefs by lazy { context.getSharedPreferences("expiry", Context.MODE_PRIVATE) }

    suspend fun check(): Unit = withContext(Dispatchers.IO) {
        val p = subscriptions.load()?.provider ?: return@withContext
        val expireS = p.expireS ?: return@withContext
        val last = prefs.getLong(EXPIRE_S, -1).takeIf { it >= 0 }?.let { Reminded(it, prefs.getInt(DAY, -1)) }
        val day = reminderDay(expireS, nowMs(), ZoneId.systemDefault(), last) ?: return@withContext
        val (title, text) = expiryWords(day, p.title)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Подписка", NotificationManager.IMPORTANCE_DEFAULT))
        // Тап — к поддержке провайдера (там продлевают); ссылки нет — в Podbor.
        val target = when (val s = p.support) {
            is Support.Link -> Intent(Intent.ACTION_VIEW, Uri.parse(s.url))
            Support.None -> Intent(context, MainActivity::class.java)
        }
        val tap = PendingIntent.getActivity(context, 1, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
        prefs.edit().putLong(EXPIRE_S, expireS).putInt(DAY, day).apply()
    }
}
