package ai.tushar.nanochat.ui.mascot

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape

private val Ink = Color(0xFF3A2419)
private val Blush = Color(0xFFFF5E57)

private val BlobBody = Path().apply {
    moveTo(50f, 14f)
    cubicTo(73f, 14f, 87f, 31f, 87f, 55f)
    cubicTo(87f, 77f, 71f, 88f, 50f, 88f)
    cubicTo(29f, 88f, 13f, 77f, 13f, 55f)
    cubicTo(13f, 31f, 27f, 14f, 50f, 14f)
    close()
}

private val Smile = Path().apply {
    moveTo(46f, 61.5f)
    quadraticTo(50f, 66f, 54f, 61.5f)
}

private fun DrawScope.ellipse(color: Color, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float = 1f) =
    drawOval(color, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2), alpha = alpha)

/** The app's round blob character, tinted with [color]. Drawn in a 100-unit box scaled to [size]. */
@Composable
fun Blob(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 100f
        scale(s, pivot = Offset.Zero) {
            translate(0f, -1f) {
                drawPath(BlobBody, color)
                ellipse(Color.White, 33f, 31f, 7f, 4.2f, alpha = 0.45f)
                ellipse(Blush, 30f, 63f, 6f, 3.4f, alpha = 0.35f)
                ellipse(Blush, 70f, 63f, 6f, 3.4f, alpha = 0.35f)
                ellipse(Ink, 39f, 53f, 4.2f, 5.6f)
                ellipse(Ink, 61f, 53f, 4.2f, 5.6f)
                ellipse(Color.White, 40.4f, 51f, 1.4f, 1.4f)
                ellipse(Color.White, 62.4f, 51f, 1.4f, 1.4f)
                drawPath(Smile, Ink, style = Stroke(width = 2.6f, cap = StrokeCap.Round))
            }
        }
    }
}

/** Round avatar: the blob sitting on a soft disc of its own color. */
@Composable
fun BlobAvatar(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(color.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Blob(color, size * 0.86f, Modifier.offset(y = size * 0.03f))
    }
}

/** Three dots that hop in turn, shown while the model works on its first token. */
@Composable
fun ThinkingDots(color: Color, modifier: Modifier = Modifier, dot: Dp = 7.dp) {
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(dot * 0.7f), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 140),
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .graphicsLayer { translationY = -lift * dot.toPx() * 0.9f; alpha = 0.45f + 0.55f * lift }
                    .size(dot)
                    .background(color, CircleShape),
            )
        }
    }
}
