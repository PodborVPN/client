package podbor.screen

/*
 * История «включить и выключить VPN» глазами человека. Одна на все ОС: автомат Мили решает, что делать, платформа
 * ([SessionPlatform]) — как это сделать у себя. Состояние автомата — открытый вопрос о согласии: оно уже живёт в
 * [MainViewModel.ask] и переживает смерть процесса, вторая копия только разошлась бы с ним.
 */

/** Что случилось: человек нажал, ViewModel дождался момента, система ответила. */
sealed interface SessionLetter {
    /** Открытие ждёт сессии ([MainViewModel.needsStart]). */
    data object StartDue : SessionLetter
    data object Retry : SessionLetter
    data object TurnOn : SessionLetter
    data object TurnOff : SessionLetter

    /** Ответ платформы на [SessionDeed.CheckConsent]: без разрешения человека движок не поднять. */
    data object ConsentRequired : SessionLetter

    /** Ответ платформы на [SessionDeed.CheckConsent]: разрешение есть или на этой ОС не нужно. */
    data object ConsentClear : SessionLetter
    data class ConsentAnswered(val granted: Boolean) : SessionLetter
}

/** Что сделать; порядок в списке — порядок исполнения. */
enum class SessionDeed {
    CheckConsent, MarkAsked, AskConsent, StartEngine, MarkIssued, FollowEngine, MarkAnswered, MarkRefused, StopEngine, Reopen,
}

private val RAISE = listOf(SessionDeed.StartEngine, SessionDeed.MarkIssued, SessionDeed.FollowEngine)

/** Шаг автомата. Пока окно согласия открыто, второго подъёма не начинаем: ответ придёт на уже заданный вопрос. */
fun sessionDeeds(consentOpen: Boolean, letter: SessionLetter): List<SessionDeed> = when (letter) {
    SessionLetter.StartDue, SessionLetter.Retry -> if (consentOpen) emptyList() else listOf(SessionDeed.CheckConsent)
    SessionLetter.ConsentRequired -> listOf(SessionDeed.MarkAsked, SessionDeed.AskConsent)
    SessionLetter.ConsentClear -> RAISE
    is SessionLetter.ConsentAnswered ->
        listOf(SessionDeed.MarkAnswered) + if (letter.granted) RAISE else listOf(SessionDeed.MarkRefused)
    SessionLetter.TurnOn -> listOf(SessionDeed.Reopen)
    SessionLetter.TurnOff -> listOf(SessionDeed.StopEngine)
}

/** Что каждая ОС умеет по-своему. Android, Linux, Windows — разные реализации одного договора. */
interface SessionPlatform {
    /** Нужно ли спросить человека, прежде чем поднимать движок. Где такого нет — false. */
    fun consentNeeded(): Boolean

    /** Показать вопрос; ответ придёт буквой [SessionLetter.ConsentAnswered]. */
    fun askConsent()
    fun startEngine()

    /** Подключиться к поднятому движку, если он живёт отдельно от экрана. */
    fun followEngine()
    fun stopEngine()

    /** Экран открыт заново: перечитать подписку и замерить сессию. */
    fun reopen()
}

/** Прогоняет буквы через автомат и передаёт действия в [MainViewModel] и [SessionPlatform]. */
class SessionStory(private val vm: MainViewModel, private val platform: SessionPlatform) {
    fun send(letter: SessionLetter) = sessionDeeds(vm.ask.value == Ask.VpnConsent, letter).forEach(::run)

    private fun run(deed: SessionDeed): Unit = when (deed) {
        SessionDeed.CheckConsent ->
            send(if (platform.consentNeeded()) SessionLetter.ConsentRequired else SessionLetter.ConsentClear)
        SessionDeed.MarkAsked -> vm.asked(Ask.VpnConsent)
        SessionDeed.AskConsent -> platform.askConsent()
        SessionDeed.StartEngine -> platform.startEngine()
        SessionDeed.MarkIssued -> vm.issued()
        SessionDeed.FollowEngine -> platform.followEngine()
        SessionDeed.MarkAnswered -> vm.answered()
        SessionDeed.MarkRefused -> vm.refused()
        SessionDeed.StopEngine -> platform.stopEngine()
        SessionDeed.Reopen -> platform.reopen()
    }
}
