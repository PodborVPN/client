package podbor.core

/**
 * Статус карточки сервиса на экране: слова — «—», «не из подписки», «ищу», «проверяю», «работает», «мёртв», «найдено»,
 * «не найдено».
 */
sealed interface CardStatus {
    data object Unknown : CardStatus

    /** Балансер сервиса указывает на сервер не из подписки: проверить нечем (слова прежнего экрана — тег и почему). */
    data class Unlisted(val tag: ServerId) : CardStatus
    data object Searching : CardStatus
    /** [relocation] — как у [ExitState]: выход и нынешняя сеть. */
    data class Checking(val ref: ExitRef, val relocation: Relocation = Relocation.Unknown) : CardStatus
    data class Works(val ref: ExitRef, val relocation: Relocation = Relocation.Unknown) : CardStatus
    data class Dead(val ref: ExitRef, val blame: Blame, val relocation: Relocation = Relocation.Unknown) : CardStatus
    data class Found(val ref: ExitRef, val probedAtMs: Long, val coverage: String) : CardStatus
    data class NotFound(val lines: List<String>) : CardStatus
}

data class ServiceCard(val service: ServiceId, val label: String, val status: CardStatus, val stopwatch: Stopwatch = Stopwatch.None)

/** Сводный экран: одна карточка сети, кнопка на всех (и общий секундомер), карточка на каждый сервис (по [ServiceId]). */
data class Board(val network: NetworkCard, val button: Button, val cards: List<ServiceCard>, val stopwatch: Stopwatch = Stopwatch.None)

/** [screens] — экраны трубопроводов; сервис без экрана — Unknown. Сеть — с экрана GENERAL_FOLLOWS, иначе с первого по ServiceId. */
fun board(services: Set<Service>, screens: Map<ServiceId, Screen>): Board {
    val sorted = services.sortedBy { it.id.id }
    val own = sorted.mapNotNull { screens[it.id] }
    val shown = listOfNotNull(screens[GENERAL_FOLLOWS]) + own
    val network = if (shown.isEmpty()) NetworkCard.Measuring(OsNetwork.None) else shown.first().network
    return Board(network, buttonOf(own.map { it.button }), sorted.map { cardOf(it, screens) }, overall(own.map { it.stopwatch }))
}

private fun cardOf(service: Service, screens: Map<ServiceId, Screen>): ServiceCard = when (val screen = screens[service.id]) {
    null -> ServiceCard(service.id, service.label, CardStatus.Unknown)
    else -> ServiceCard(service.id, service.label, statusOf(screen), screen.stopwatch)
}

/**
 * Ищу, пока идёт хоть один; «Подобрать», если на открытии хоть одному есть что подбирать (зелёная рядом с мёртвым
 * выходом обманула бы); зелёная, если нашлось хоть у одного; красная, только если у всех (с экраном) красная.
 */
private fun buttonOf(buttons: List<Button>): Button = when {
    Button.Searching in buttons -> Button.Searching
    Button.Ready in buttons -> Button.Ready
    Button.Green in buttons -> Button.Green
    buttons.isNotEmpty() && buttons.all { it == Button.Red } -> Button.Red
    else -> Button.Idle
}

private fun statusOf(screen: Screen): CardStatus = when (val r = screen.result) {
    // Пустой результат при поиске — «ищу»; иначе ещё ничего не известно.
    ResultCard.Empty -> if (screen.button == Button.Searching) CardStatus.Searching else CardStatus.Unknown
    is ResultCard.Found -> CardStatus.Found(ExitRef(r.serverName, r.transport), r.probedAtMs, r.coverage)
    is ResultCard.NotFound -> CardStatus.NotFound(r.lines)
    is ResultCard.Exit -> statusOf(r.state)
}

private fun statusOf(e: ExitState): CardStatus = when (e) {
    ExitState.Off -> CardStatus.Unknown
    is ExitState.Unlisted -> CardStatus.Unlisted(e.tag)
    is ExitState.Checking -> CardStatus.Checking(e.ref, e.relocation)
    is ExitState.Works -> CardStatus.Works(e.ref, e.relocation)
    is ExitState.Dead -> CardStatus.Dead(e.ref, e.blame, e.relocation)
}
