package podbor.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import podbor.screen.Page
import podbor.screen.Subscriptions
import podbor.screen.card
import podbor.screen.pageOf
import podbor.screen.errorOf

/**
 * Страница подписки под сетью: «Добавь подписку» и анализ — одна страница, переход между ними внутри (плавно), без
 * транзакции. Что показывать — [pageOf] от фактов Activity.
 */
class SubscriptionFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val screen = requireActivity() as MainActivity
            setContent {
                val phase by screen.vm.scan.collectAsStateWithLifecycle()
                val subs by screen.vm.subscriptions.collectAsStateWithLifecycle()
                val skipped by screen.vm.skipped.collectAsStateWithLifecycle()
                val sheet by screen.vm.sheet.collectAsStateWithLifecycle()
                val duplicate by screen.vm.duplicate.collectAsStateWithLifecycle()
                PodborTheme {
                    // Поверх обеих страниц: ничего не подошло — на «Добавь подписку», часть — на анализе.
                    val notParsed = skipped
                    val repeated = duplicate
                    val opened = sheet
                    val loaded = subs
                    val shelf = if (loaded == null) Subscriptions.None else loaded
                    if (notParsed != null) key(notParsed) { SkippedSheet(notParsed, onDismiss = screen.vm::dismissSkipped) }
                    if (repeated != null) RepeatDialog(repeated, onRefresh = screen.vm::refreshDuplicate, onDismiss = screen.vm::dismissDuplicate)
                    // Шторка подписки — одна на карточку: шаги внутри (подтверждение, «обновляю») не пересоздают её.
                    if (opened != null) {
                        key(opened.card().id) {
                            SubscriptionSheet(
                                opened, System.currentTimeMillis(),
                                onPick = screen.vm::pick, onReanalyze = screen.vm::reanalyze,
                                onAskDelete = screen.vm::askDelete, onConfirmDelete = screen.vm::confirmDelete,
                                onSupport = screen::openSupport, onDismiss = screen.vm::closeSheet,
                            )
                        }
                    }
                    // По виду страницы, не по доске: новая доска анализа — не новый переход.
                    AnimatedContent(
                        pageOf(shelf, phase),
                        contentKey = { it::class },
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                        label = "страница",
                    ) { page ->
                        when (page) {
                            Page.AddSubscription ->
                                SubscriptionScreen(
                                    errorOf(phase), onInput = screen::input, onQr = screen::scanQr, onSupport = screen::openSupport,
                                    subscriptions = shelf, nowMs = System.currentTimeMillis(), onCard = screen.vm::openCard,
                                )
                            is Page.Scanning -> ScanScreen(page.board, onOk = screen::finishScan)
                            // Миг до транзакции на главный — пусто.
                            Page.Main -> Unit
                        }
                    }
                }
            }
        }
}
