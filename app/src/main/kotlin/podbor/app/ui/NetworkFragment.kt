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
import podbor.screen.DnsLine
import podbor.screen.dnsLineOf
import podbor.core.networkKey
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

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
                val fixes by screen.dnsFixes.all.collectAsStateWithLifecycle()
                // Починено ли на этой сети: сети нет — ремонт не про неё.
                val fixed = when (val here = networkKey(board.network.os)) {
                    null -> false
                    else -> here in fixes
                }
                PodborTheme {
                    NetworkScreen(
                        board.network, BuildConfig.VERSION_NAME, update, screen::openLink, networkTraffic(pulse, board.network),
                        dns = dnsLineOf(board.network, fixed), onDns = screen::askDns,
                    )
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
fun NetworkScreen(
    card: NetworkCard,
    version: String,
    update: Offer? = null,
    onUpdate: (String) -> Unit = {},
    traffic: String? = null,
    /** DNS сети в строке улики ([dnsLineOf]); [onDns] — 🔧: шторка ремонта. */
    dns: DnsLine = DnsLine.Quiet,
    onDns: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = Space.screen)
            .padding(top = Space.m),
    ) {
        // Высота строки одна с чипом и без: появившись, чип не сдвигает страницу.
        TierWithTraffic("СЕТЬ", traffic, Modifier.height(Space.control)) {
            if (update == null) {
                Text("v$version", color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            } else {
                AssistChip(
                    onClick = { onUpdate(update.url) },
                    label = { Text("обновить до ${update.version}", style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    shape = Corner,
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = PodborColors.Accent,
                        labelColor = PodborColors.Background,
                        leadingIconContentColor = PodborColors.Background,
                    ),
                    border = null,
                )
            }
        }
        NetworkTier(card, dns, onDns)
    }
}

/**
 * Строка прибора со значком слева и разделителем снизу. Высота постоянная — с действием справа ([action]) и без: кнопка
 * появляется, а плашка не прыгает.
 */
@Composable
private fun IconRow(@DrawableRes icon: Int, text: String, dim: Boolean = false, color: Color = Muted, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(Space.control).alpha(if (dim) DIM else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Muted, modifier = Modifier.size(Space.icon))
        Spacer(Modifier.width(Space.m))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        action?.invoke()
    }
    HorizontalDivider(thickness = Space.hairline, color = Rule)
}

/** 🔧 в строке улики — кнопкой-значком: касание 48dp выступает за строку 40dp, строка не растёт. */
@Composable
private fun Wrench(onClick: () -> Unit) {
    IconSquareButton(R.drawable.ic_wrench, "DNS сети", onClick)
}

@Composable
private fun NetworkTier(card: NetworkCard, dns: DnsLine, onDns: () -> Unit) {
    val os = card.os
    val evidence = when (card) {
        is NetworkCard.Measuring -> null
        is NetworkCard.Measured -> card.evidence
    }
    val color = colorOf(toneOf(card))
    Row(Modifier.fillMaxWidth().height(Space.control), verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(Space.m))
        Text(
            networkTitle(card).uppercase(),
            color = color,
            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = CapsSpacing),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(thickness = Space.hairline, color = Rule)
    // Строка оператора — только у сотовой и всегда, от замера до итога: есть она или нет, решает сеть ОС, а не
    // заголовок, иначе страница под сетью прыгает. Wi-Fi назван в заголовке. Вышка — только с разрешением геолокации.
    when (os) {
        is OsNetwork.Cellular -> IconRow(R.drawable.ic_cell_tower, listOfNotNull(operatorOf(os), towerLine(os)).joinToString(" · "))
        OsNetwork.Wifi, OsNetwork.None -> Unit
    }
    // Строка улики держит место и во время замера (прошлая улика приглушена): страница под сетью не прыгает.
    val last = remember { mutableStateOf<String?>(null) }
    SideEffect { if (evidence != null) last.value = evidence }
    // DNS сети — в той же строке: сломан — словами янтарём и 🔧 «починить», починен — как обошли и 🔧 «вернуть».
    when (dns) {
        // Улики ещё не было — строка держит место словами о том, что проверяем, а не пустотой.
        DnsLine.Quiet -> IconRow(R.drawable.ic_globe, evidence ?: last.value ?: "проверяю, отвечает ли заграница…", dim = evidence == null)
        is DnsLine.Broken -> IconRow(R.drawable.ic_globe, dns.text, color = PodborColors.Accent) { Wrench(onDns) }
        is DnsLine.Fixed -> IconRow(R.drawable.ic_globe, dns.text) { Wrench(onDns) }
    }
}
