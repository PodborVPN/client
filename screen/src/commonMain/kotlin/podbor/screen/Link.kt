package podbor.screen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import podbor.core.Board
import podbor.core.Button
import podbor.core.Stopwatch

/** Связь экрана с VPN: экран ядра осмыслен только при поднятой сессии; остальное — словами вместо него. */
enum class Link { Starting, Up, Failed, Off, Consent, Refused }

/**
 * Что экран сам сделал для VPN: ничего, ждёт согласия, получил отказ, выдал старт (сервис ещё не начал подъём),
 * подъём идёт (видели Starting после своего старта).
 */
enum class Start { None, AwaitingConsent, Refused, Issued, Running }

/**
 * Факты экрана о VPN — без Android: свой последний шаг ([start]), привязан ли сервис и последнее, что сказала его
 * сессия. «Поднимаю VPN…» — только из своего выданного старта, не из «сервиса нет»: сервис — единственный
 * держатель VPN, и если его нет и старта не было, VPN выключен.
 */
data class LinkFacts(
    val start: Start = Start.None,
    val bound: Boolean = false,
    val session: SessionView? = null,
) {
    fun askingConsent() = copy(start = Start.AwaitingConsent)
    fun refused() = copy(start = Start.Refused)
    /**
     * Старт выдан. Если привязанный сервис уже поднят, старт ничего не меняет и нового Up не будет — ждать нечего,
     * иначе «поднимаю VPN…» висело бы вечно.
     */
    fun issued() = copy(start = if (bound && session == SessionView.Up) Start.None else Start.Issued)
    fun bound() = copy(bound = true)
    fun unbound() = copy(bound = false)

    /**
     * Сессия сказала своё. Выданный старт: Starting — подъём пошёл (Running); Up/Failed — итог. Down до Starting —
     * устаревшее значение прежней сессии, старт ждёт дальше. Идущий подъём кончается любым итогом, в том числе Down
     * (стоп посреди подъёма) — иначе «поднимаю VPN…» висело бы вечно.
     */
    fun seen(s: SessionView): LinkFacts {
        val next = when (start) {
            Start.Issued -> when (s) {
                SessionView.Starting -> Start.Running
                SessionView.Up, is SessionView.Failed -> Start.None
                SessionView.Down -> Start.Issued
            }
            Start.Running -> when (s) {
                SessionView.Starting -> Start.Running
                SessionView.Up, is SessionView.Failed, SessionView.Down -> Start.None
            }
            Start.None, Start.AwaitingConsent, Start.Refused -> start
        }
        return copy(session = s, start = next)
    }
}

fun linkOf(f: LinkFacts): Link = when (f.start) {
    Start.AwaitingConsent -> Link.Consent
    Start.Refused -> Link.Refused
    Start.Issued -> Link.Starting
    // Идущий подъём — по сессии: Starting, пока сервис привязан; ушёл посреди — Off.
    Start.None, Start.Running -> when (val s = f.session) {
        // Неудачный подъём остаётся итогом и после ухода сервиса.
        is SessionView.Failed -> Link.Failed
        null, SessionView.Down -> Link.Off
        SessionView.Starting -> if (f.bound) Link.Starting else Link.Off
        SessionView.Up -> if (f.bound) Link.Up else Link.Off
    }
}

/**
 * Что большая кнопка показывает и умеет. VPN поднят — подбор (жмётся по состоянию кнопки ядра) и лепесток «выкл»;
 * выключен — «включить»; поднимается, ждёт согласия, отказан, не поднялся — ничего (для неудачи есть «повторить»).
 */
sealed interface Face {
    /** [stopwatch] — общий секундомер экрана: пока ищет хоть один сервис — идёт. */
    data class Press(val button: Button, val stopwatch: Stopwatch = Stopwatch.None) : Face
    data object TurnOn : Face
    data object Waiting : Face
}

fun faceOf(board: Board, link: Link): Face = when (link) {
    Link.Up -> Face.Press(board.button, board.stopwatch)
    Link.Off -> Face.TurnOn
    Link.Starting, Link.Failed, Link.Consent, Link.Refused -> Face.Waiting
}

/**
 * Последнее, что сказал сервис, даже когда его уже нет: отвязка после стопа (null) не сбрасывает состояние.
 * Новый сервис — новые значения.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <S : Any, T> Flow<S?>.keepLast(select: (S) -> Flow<T>): Flow<T> = filterNotNull().flatMapLatest(select)
