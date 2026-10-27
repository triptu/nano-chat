package ai.tushar.nanochat.ui

import ai.tushar.nanochat.AppGraph
import ai.tushar.nanochat.R
import ai.tushar.nanochat.graph
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
inline fun <reified VM : ViewModel> graphViewModel(key: String? = null, crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalContext.current.graph
    return viewModel(key = key, factory = viewModelFactory { initializer { create(graph) } })
}

/** Phosphor icons, converted to vector drawables in res/drawable. */
object Icons {
    @DrawableRes val Back = R.drawable.ic_arrow_left
    @DrawableRes val Send = R.drawable.ic_arrow_up_bold
    @DrawableRes val Stop = R.drawable.ic_stop_fill
    @DrawableRes val More = R.drawable.ic_dots_three_vertical
    @DrawableRes val Info = R.drawable.ic_info
    @DrawableRes val Add = R.drawable.ic_plus_bold
    @DrawableRes val Delete = R.drawable.ic_trash
    @DrawableRes val Edit = R.drawable.ic_pencil_simple
    @DrawableRes val Clear = R.drawable.ic_broom
    @DrawableRes val Nerd = R.drawable.ic_flask
    @DrawableRes val Chevron = R.drawable.ic_caret_right
    @DrawableRes val Close = R.drawable.ic_x
    @DrawableRes val Check = R.drawable.ic_check
    @DrawableRes val Download = R.drawable.ic_download_simple
    @DrawableRes val Attach = R.drawable.ic_plus
    @DrawableRes val Photo = R.drawable.ic_image
    @DrawableRes val Camera = R.drawable.ic_camera
    @DrawableRes val Mic = R.drawable.ic_microphone
    @DrawableRes val Play = R.drawable.ic_play_fill
    @DrawableRes val Pause = R.drawable.ic_pause_fill
    @DrawableRes val Waveform = R.drawable.ic_waveform
    @DrawableRes val Phone = R.drawable.ic_device_mobile
    @DrawableRes val Warning = R.drawable.ic_warning_circle
}

@Composable
fun Ico(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = LocalContentColor.current) =
    Icon(painterResource(id), contentDescription, modifier.size(size), tint = tint)

/** "now", "5m", "3h", "Yesterday", "Mon", "12 Mar". */
fun relativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val mins = (nowMs - epochMs) / 60_000
    val then = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(then, today)
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        days == 0L -> "${mins / 60}h"
        days == 1L -> "Yesterday"
        days < 7 -> then.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        then.year == today.year -> then.format(DateTimeFormatter.ofPattern("d MMM"))
        else -> then.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}

