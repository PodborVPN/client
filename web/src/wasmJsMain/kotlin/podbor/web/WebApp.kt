package podbor.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import podbor.app.ui.MainScreen
import podbor.app.ui.Muted
import podbor.app.ui.PodborColors
import podbor.app.ui.PodborTheme
import podbor.app.ui.PrimaryButton
import podbor.app.ui.RepeatDialog
import podbor.app.ui.ScanScreen
import podbor.app.ui.SkippedSheet
import podbor.app.ui.Space
import podbor.app.ui.SubscriptionScreen
import podbor.app.ui.SubscriptionSheet
import podbor.remote.RemotePort
import podbor.screen.MainViewModel
import podbor.screen.Page
import podbor.screen.SessionLetter
import podbor.screen.SessionStory
import podbor.screen.Subscriptions
import podbor.screen.card
import podbor.screen.errorOf
import podbor.screen.linkOf
import podbor.screen.pageOf
import kotlin.time.Clock

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** Ширина колонки экрана на большом окне. */
private val COLUMN = 480.dp

/** Страницы — по тому же [MainViewModel.page], что на телефоне меняет фрагменты. */
@Composable
fun WebApp(vm: MainViewModel, port: RemotePort, story: SessionStory, scope: CoroutineScope) {
    val page by vm.page.collectAsState()
    PodborTheme {
        // Экраны свёрстаны под телефон: на широком окне — колонкой по центру.
        Box(Modifier.fillMaxSize().background(PodborColors.Background), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = COLUMN).fillMaxHeight()) {
                when (page) {
                    null -> Unit
                    Page.Main -> MainPage(vm, port, story)
                    Page.AddSubscription, is Page.Scanning -> SubscriptionPage(vm, port, scope)
                }
            }
        }
    }
}

@Composable
private fun MainPage(vm: MainViewModel, port: RemotePort, story: SessionStory) {
    val board by vm.board.collectAsState()
    val known by vm.facts.collectAsState()
    val origin by vm.origin.collectAsState()
    val subscription by vm.subscriptionNote.collectAsState()
    val routes by vm.routes.collectAsState()
    val countries by vm.countries.collectAsState()
    val pulse by vm.pulse.collectAsState()
    MainScreen(
        board, linkOf(known), origin, null, subscription,
        onPress = vm::press,
        onOff = { story.send(SessionLetter.TurnOff) },
        onOn = { story.send(SessionLetter.TurnOn) },
        onRetry = { story.send(SessionLetter.Retry) },
        onShare = null,
        onChangeSubscription = vm::changeSubscription,
        onSupport = ::openInNewTab,
        onDonate = null,
        pulse = pulse,
        countries = countries,
        onCountries = vm::setExitFilter,
        history = vm::history,
        routes = routes,
        exitCountry = port::exitCountry,
    )
}

@Composable
private fun SubscriptionPage(vm: MainViewModel, port: RemotePort, scope: CoroutineScope) {
    val phase by vm.scan.collectAsState()
    val subs by vm.subscriptions.collectAsState()
    val skipped by vm.skipped.collectAsState()
    val sheet by vm.sheet.collectAsState()
    val duplicate by vm.duplicate.collectAsState()
    skipped?.let { s -> key(s) { SkippedSheet(s, onDismiss = vm::dismissSkipped) } }
    duplicate?.let { RepeatDialog(it, onRefresh = vm::refreshDuplicate, onDismiss = vm::dismissDuplicate) }
    sheet?.let { s ->
        key(s.card().id) {
            SubscriptionSheet(
                s, nowMs(),
                onPick = vm::pick, onReanalyze = vm::reanalyze,
                onAskDelete = vm::askDelete, onConfirmDelete = vm::confirmDelete,
                onSupport = ::openInNewTab, onDismiss = vm::closeSheet,
            )
        }
    }
    when (val page = pageOf(subs ?: Subscriptions.None, phase)) {
        Page.AddSubscription -> SubscriptionScreen(
            errorOf(phase), ::openInNewTab, subs ?: Subscriptions.None, nowMs(), vm::openCard,
            hint = "ссылку на подписку или сами конфиги — вставь сюда",
        ) { PastedSubscription { text -> scope.launch { vm.input(port.parse(text)) } } }
        is Page.Scanning -> ScanScreen(page.board, onOk = vm::finishScan)
        Page.Main -> Unit
    }
}

/** Поле вместо буфера и QR: страница не просит у браузера доступ к буферу. Текст секретен — никуда, кроме демона. */
@Composable
private fun PastedSubscription(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            text,
            { text = it },
            Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 240.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = { Text("https://… или vless://…", color = Muted, style = MaterialTheme.typography.bodyMedium) },
        )
        Spacer(Modifier.height(Space.m))
        PrimaryButton("добавить", { onSubmit(text) }, Modifier.fillMaxWidth())
    }
}
