package podbor.app.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import podbor.app.ui.MainActivity
import podbor.screen.Tap
import podbor.screen.Tray
import podbor.screen.TrayOff
import podbor.screen.tapOf

/**
 * Статус шторки процесса: пишет служба VPN, читает плитка. Службы нет — выключен. Каждое изменение будит плитку
 * ([TileService.requestListeningState]): она активная и сама состояние не опрашивает.
 */
object TrayBus {
    private val current = MutableStateFlow(TrayOff)
    val tray: StateFlow<Tray> = current.asStateFlow()

    fun publish(context: Context, tray: Tray) {
        if (current.value == tray) return
        current.value = tray
        TileService.requestListeningState(context, ComponentName(context, PodborTile::class.java))
    }

    fun reset(context: Context) = publish(context, TrayOff)
}

/** Плитка шторки: вкл/выкл одним тапом и статус подписью — тот же, что в уведомлении ([trayOf]). */
class PodborTile : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var watching: Job? = null

    override fun onStartListening() {
        watching = scope.launch { TrayBus.tray.collect(::render) }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
    }

    override fun onClick() {
        when (tapOf(TrayBus.tray.value, consented = VpnService.prepare(this) == null)) {
            Tap.Stop -> {
                Log.i(TAG, "плитка: выключить")
                startService(Intent(this, PodborVpnService::class.java).setAction(PodborVpnService.ACTION_STOP))
            }
            // Старт из шторки — со своим замером открытия: экрана нет, а статус должен быть честным с первого раза.
            // Система не дала поднять службу из фона — открываем Podbor, там включат.
            Tap.Start -> {
                Log.i(TAG, "плитка: включить")
                Either.catch {
                    startForegroundService(
                        Intent(this, PodborVpnService::class.java)
                            .setAction(PodborVpnService.ACTION_START)
                            .putExtra(PodborVpnService.EXTRA_OPEN, true),
                    )
                }.onLeft { e ->
                    Log.i(TAG, "плитка: служба не поднялась из шторки (${e::class.simpleName}) — открываю Podbor")
                    openApp()
                }
            }
            Tap.OpenApp -> {
                Log.i(TAG, "плитка: согласия на VPN нет — открываю Podbor")
                openApp()
            }
        }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun render(tray: Tray) {
        val tile = qsTile ?: return
        tile.state = if (tray.on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = applicationInfo.loadLabel(packageManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = tray.line
        tile.contentDescription = "${tile.label}: ${tray.line}"
        tile.updateTile()
    }

    /** Android 14+ — через PendingIntent; до него другого вызова нет, устаревший — только там. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val TAG = "Podbor"
    }
}
