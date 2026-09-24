package net.helcel.owu.activity

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The app's face, drawn rather than stamped from the launcher icon so it can
 * do the one thing an icon cannot: now and then the left eye closes and OwU
 * becomes UwU. Both eyes are one shape ([eye]) at different points of the same
 * animation. Tapping winks on demand; left alone it winks rarely.
 */
@Composable
fun OwuFace(modifier: Modifier = Modifier) {
    val lid = remember { Animatable(0f) }   // 0 = open (O), 1 = shut (U)
    val scope = rememberCoroutineScope()

    suspend fun wink() {
        lid.animateTo(1f, tween(160))
        delay(340)
        lid.animateTo(0f, tween(240))
    }

    // Rarely, and never on a schedule anyone could set a watch by.
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(9_000, 24_000))
            wink()
        }
    }

    val face = MaterialTheme.colors.onPrimary
    val disc = MaterialTheme.colors.primary
    Canvas(
        modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { scope.launch { wink() } }
            .semantics { contentDescription = "OwU" },
    ) {
        val unit = size.minDimension / 108f
        fun p(v: Float) = v * unit
        val width = p(5f)

        // One colour, edge to edge, and the face on it - what the launcher
        // icon is, minus the shape the launcher itself decides.
        drawRect(disc)

        scale(0.88f, pivot = Offset(p(54f), p(52f))) {
            // Both eyes stand on the same baseline, y = 60, and reach y = 44.
            eye(cx = p(32f), cy = p(52f), r = p(8f), shut = lid.value, color = face, width = width)
            eye(cx = p(76f), cy = p(52f), r = p(8f), shut = 1f, color = face, width = width)

            // The mouth stands on the same baseline as the eyes, with a gap
            // either side so the three glyphs read as three.
            drawPath(
                path = Path().apply {
                    moveTo(p(47f), p(50f))
                    lineTo(p(50.5f), p(60f))
                    lineTo(p(54f), p(52f))
                    lineTo(p(57.5f), p(60f))
                    lineTo(p(61f), p(50f))
                },
                color = face,
                style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/**
 * One eye between open and shut, in two strokes: the U - bottom half-circle
 * and two vertical stems - and the lid arcing over it. Closing raises the
 * stems and lets the lid sink onto them, fading. The bottom never moves, the
 * sides stay vertical, and [shut] = 1 is exactly the other eye's U: an eye
 * shuts, it does not shrink or square off.
 */
private fun DrawScope.eye(cx: Float, cy: Float, r: Float, shut: Float, color: Color, width: Float) {
    val stroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Up fast, so the U is there before the lid has finished leaving.
    val stem = r * (1f - (1f - shut) * (1f - shut))
    drawPath(
        Path().apply {
            moveTo(cx - r, cy - stem)
            lineTo(cx - r, cy)
            // Left, round the bottom, to the right.
            arcTo(Rect(cx - r, cy - r, cx + r, cy + r), 180f, -180f, false)
            lineTo(cx + r, cy - stem)
        },
        color,
        style = stroke,
    )
    if (shut >= 1f) return
    val bulge = r * (1f - shut)
    drawPath(
        Path().apply {
            moveTo(cx - r, cy - stem)
            // Handles four thirds of the bulge up: the usual approximation of
            // a half circle, and a shallower arc as the lid comes down.
            cubicTo(cx - r, cy - stem - bulge * 4f / 3f, cx + r, cy - stem - bulge * 4f / 3f, cx + r, cy - stem)
        },
        color,
        alpha = (1f - shut) * (1f - shut),
        style = stroke,
    )
}
