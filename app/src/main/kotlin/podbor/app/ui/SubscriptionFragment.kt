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
                val has by screen.vm.subscribed.collectAsStateWithLifecycle()
                val skipped by screen.vm.skipped.collectAsStateWithLifecycle()
                PodborTheme {
                    // Поверх обеих страниц: ничего не подошло — на «Добавь подписку», часть — на анализе.
                    skipped?.let { s -> key(s) { SkippedSheet(s, onDismiss = screen.vm::dismissSkipped) } }
                    // По виду страницы, не по доске: новая доска анализа — не новый переход.
                    AnimatedContent(
                        pageOf(has == true, phase),
                        contentKey = { it::class },
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                        label = "страница",
                    ) { page ->
                        when (page) {
                            Page.AddSubscription -> SubscriptionScreen(errorOf(phase), onInput = screen::input, onQr = screen::scanQr)
                            is Page.Scanning -> ScanScreen(page.board, onOk = screen::finishScan)
                            // Миг до транзакции на главный — пусто.
                            Page.Main -> Unit
                        }
                    }
                }
            }
        }
}
