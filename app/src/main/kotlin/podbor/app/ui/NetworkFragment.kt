package podbor.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import podbor.app.BuildConfig
import podbor.app.R
import podbor.screen.Offer
import podbor.screen.networkTraffic
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.networkTitle
import podbor.core.operatorOf
import podbor.core.towerLine
import podbor.screen.toneOf

/** Сеть — всегда сверху, над любой страницей: плашка из сводного экрана сервиса (и до VPN — замер линии мимо туннеля). */
class NetworkFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // Отступы окна нужны и странице ниже: на API 26–29 поглощённые вставки до соседа не доходят.
            consumeWindowInsets = false
            val screen = requireActivity() as MainActivity
            setContent {
                val board by screen.vm.board.collectAsStateWithLifecycle()
                val update by screen.vm.update.collectAsStateWithLifecycle()
                val pulse by screen.vm.pulse.collectAsStateWithLifecycle()
                PodborTheme {
                    NetworkScreen(board.network, BuildConfig.VERSION_NAME, update, screen::openLink, networkTraffic(pulse, board.network))
                }
            }
        }
}

/**
 * Ярус «СЕТЬ»: сценарий цветом, оператор и вышка, улика; справа в строке яруса — версия, а есть новее на GitHub ([update])
 * — янтарный чип с новой версией: эта строка видна на любой странице, сколько бы карточек ни было ниже. Сразу за словом
 * «СЕТЬ» — трафик сессии ([traffic], только при поднятом VPN): серым, мелко, цифры одной ширины — не прыгают. Отступ
 * сверху — под строку состояния.
 */
@Composable
fun NetworkScreen(card: NetworkCard, version: String, update: Offer? = null, onUpdate: (String) -> Unit = {}, traffic: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
    ) {
        // Высота строки одна с чипом и без: появившись, чип не сдвигает страницу.
        TierWithTraffic("СЕТЬ", traffic, Modifier.height(40.dp)) {
            if (update == null) {
                Text("v$version", color = Muted, style = MaterialTheme.typography.labelSmall)
            } else {
                AssistChip(
                    onClick = { onUpdate(update.url) },
                    label = { Text("обновить до ${update.version}", style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    shape = RoundedCornerShape(8.dp),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = PodborColors.Accent,
                        labelColor = PodborColors.Background,
                        leadingIconContentColor = PodborColors.Background,
                    ),
                    border = null,
                )
            }
        }
        NetworkTier(card)
    }
}

/** Строка прибора со значком слева и разделителем снизу. */
@Composable
private fun IconRow(@DrawableRes icon: Int, text: String, dim: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(if (dim) 0.4f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(thickness = 1.dp, color = Rule)
}

@Composable
private fun NetworkTier(card: NetworkCard) {
    val (os, evidence) = when (card) {
        is NetworkCard.Measuring -> card.os to null
        is NetworkCard.Measured -> card.os to card.evidence
    }
    val color = colorOf(toneOf(card))
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(12.dp))
        Text(networkTitle(card).uppercase(), color = color, style = MaterialTheme.typography.titleMedium)
    }
    HorizontalDivider(thickness = 1.dp, color = Rule)
    // Строка оператора — только у сотовой и всегда, от замера до итога: есть она или нет, решает сеть ОС, а не
    // заголовок, иначе страница под сетью прыгает. Wi-Fi назван в заголовке. Вышка — только с разрешением геолокации.
    when (os) {
        is OsNetwork.Cellular -> IconRow(R.drawable.ic_cell_tower, listOfNotNull(operatorOf(os), towerLine(os)).joinToString(" · "))
        OsNetwork.Wifi, OsNetwork.None -> Unit
    }
    // Строка улики держит место и во время замера (прошлая улика приглушена): страница под сетью не прыгает.
    val last = remember { mutableStateOf<String?>(null) }
    SideEffect { if (evidence != null) last.value = evidence }
    IconRow(R.drawable.ic_globe, evidence ?: last.value ?: "", dim = evidence == null)
}
