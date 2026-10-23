package podbor.app.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfoLte
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import podbor.core.OsNetwork
import podbor.core.Tower

/** Вид нижележащей сети по ConnectivityManager (не транспорт VPN-сервера из ядра). */
sealed interface Transport {
    data object Cellular : Transport
    data object Wifi : Transport
    data object None : Transport
}

/** Зарегистрированная LTE-ячейка как её отдал Android: недоступное поле — CellInfo.UNAVAILABLE (Int.MAX_VALUE). */
data class CellSnapshot(val cellId: Long, val earfcn: Int, val rsrp: Int)

/** RSRP по 3GPP TS 36.133: −140…−44 дБм; всё вне — мусор модема, а не сигнал. */
private val RSRP_DBM = -140..-44

/**
 * Вышка для ядра. Нет разрешения или данных (null) и недоступные значения Android → Unknown:
 * иначе экран показал бы «−2147483647».
 */
fun towerOf(info: CellSnapshot?): Tower = when {
    info == null -> Tower.Unknown
    info.cellId == Int.MAX_VALUE.toLong() || info.earfcn == Int.MAX_VALUE || info.rsrp !in RSRP_DBM -> Tower.Unknown
    else -> Tower.Known(info.cellId, info.earfcn, info.rsrp)
}

/**
 * Что телефония говорит о сотовой связи. sim* — SIM подписки данных (устойчиво: ключ памяти ядра),
 * network* — сеть, в которой она сейчас («MTS 5G», «MTS LTE», чужая в роуминге) — меняется на ходу.
 */
data class Radio(val simName: String?, val simMccMnc: String?, val networkName: String?, val networkMccMnc: String?)

/**
 * Сеть ОС для ядра. Сотовая без имени оператора называется по MCC/MNC — ключ памяти остаётся устойчивым.
 * [operator] и [mccMnc] — оператора SIM с данными, а не отображаемое имя сети.
 */
fun osNetworkOf(transport: Transport, operator: String?, mccMnc: String?, tower: Tower): OsNetwork = when (transport) {
    Transport.Cellular -> {
        val code = mccMnc?.takeIf { it.isNotBlank() }
        OsNetwork.Cellular(operator?.takeIf { it.isNotBlank() } ?: code ?: "?", code ?: "?", tower)
    }
    Transport.Wifi -> OsNetwork.Wifi
    Transport.None -> OsNetwork.None
}

/** Оператор — только от SIM: имя сети плавает с 5G/LTE и роумингом и расщепило бы память ядра (memoryKey). */
fun osNetworkOf(transport: Transport, radio: Radio, tower: Tower): OsNetwork =
    osNetworkOf(transport, radio.simName, radio.simMccMnc, tower)

/**
 * Край телефонии для SIM с данными: при двух SIM и оператор, и ячейка — той подписки, через которую идут данные.
 * TelephonyManager берётся на каждый вызов: подписку данных можно переключить на ходу.
 */
class Telephony(private val context: Context) {

    private fun dataTm(): TelephonyManager {
        val base = context.getSystemService(TelephonyManager::class.java)
        val id = SubscriptionManager.getDefaultDataSubscriptionId()
        return if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) base else base.createForSubscriptionId(id)
    }

    /**
     * Обслуживающая LTE-ячейка подписки данных. allCellInfo требует ACCESS_FINE_LOCATION;
     * без него — null (Tower.Unknown), а не SecurityException.
     */
    fun cell(tm: TelephonyManager = dataTm()): CellSnapshot? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lte = tm.allCellInfo.orEmpty().filterIsInstance<CellInfoLte>().firstOrNull { it.isRegistered } ?: return null
        return CellSnapshot(lte.cellIdentity.ci.toLong(), lte.cellIdentity.earfcn, lte.cellSignalStrength.rsrp)
    }

    fun osNetwork(transport: Transport): OsNetwork = dataTm().let { tm -> osNetworkOf(transport, radio(tm), towerOf(cell(tm))) }

    /** Сеть ОС без вышки (без allCellInfo): дёшево для частых вопросов — ключ итога «на этой сети» вышки не знает. */
    fun osNetworkLight(transport: Transport): OsNetwork = osNetworkOf(transport, radio(dataTm()), Tower.Unknown)

    private fun radio(tm: TelephonyManager) = Radio(tm.simOperatorName, tm.simOperator, tm.networkOperatorName, tm.networkOperator)
}
