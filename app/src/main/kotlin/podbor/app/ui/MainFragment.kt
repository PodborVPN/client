package podbor.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import podbor.app.BuildConfig
import podbor.screen.linkOf

/** Главная страница под сетью: кнопка и текущий VPN. Состояние — потоки Activity и сервиса, не поля фрагмента. */
class MainFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val screen = requireActivity() as MainActivity
            setContent {
                val board by screen.boards.collectAsStateWithLifecycle()
                val known by screen.facts.collectAsStateWithLifecycle()
                val note by screen.shareNote.collectAsStateWithLifecycle()
                val origin by screen.origin.collectAsStateWithLifecycle()
                val subscription by screen.subscriptionNote.collectAsStateWithLifecycle()
                PodborTheme {
                    MainScreen(
                        board, linkOf(known), origin, note, subscription,
                        onPress = screen::press, onOff = screen::turnOff, onOn = screen::turnOn, onRetry = screen::retry,
                        onShare = screen::share, onChangeSubscription = screen::changeSubscription, onSupport = screen::openSupport,
                        onDonate = if (BuildConfig.SELF_DISTRIBUTED) screen::donate else null,
                    )
                }
            }
        }
}
