package com.sarvam.voiceassistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * The app's signature: a row of rounded bars in the brand accents.
 *
 * Still, it is decoration on the welcome and lock screens. With a microphone [level], it
 * moves with your voice while you speak — the same shape, alive.
 */
@Composable
fun Waveform(
    modifier: Modifier = Modifier,
    level: Float? = null,
    bars: Int = 28,
    colors: List<Color> = BoliyanColors.accents,
) {
    val phase = if (level != null) {
        val transition = rememberInfiniteTransition(label = "wave")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        ).value
    } else {
        0f
    }

    Canvas(modifier) {
        val gap = size.width / (bars * 2.2f)
        val barWidth = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            // A fixed, symmetric silhouette so the still version reads as a waveform.
            val envelope = 0.35f + 0.65f * sin(PI * (i + 0.5) / bars).toFloat()
            val ripple = 0.55f + 0.45f * sin(i * 1.7f + phase).let { it * it }
            val amount = if (level == null) {
                envelope * (0.45f + 0.55f * ((i * 37) % 11) / 10f)
            } else {
                (0.12f + envelope * ripple * (0.25f + level.coerceIn(0f, 1f) * 0.75f))
            }.coerceIn(0.08f, 1f)

            val height = size.height * amount
            drawRoundRect(
                color = colors[i % colors.size],
                topLeft = Offset(i * (barWidth + gap), (size.height - height) / 2),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}

/** "बो" — the assistant's mark, used as its avatar and on the lock screen. */
@Composable
fun Monogram(size: Dp, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = BoliyanColors.Ink,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "बो",
                color = BoliyanColors.Marigold,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

private val greetings = listOf(
    "नमस्ते" to "Hindi",
    "નમસ્તે" to "Gujarati",
    "வணக்கம்" to "Tamil",
    "নমস্কার" to "Bengali",
    "నమస్కారం" to "Telugu",
    "ನಮಸ್ಕಾರ" to "Kannada",
    "നമസ്കാരം" to "Malayalam",
    "ਸਤ ਸ੍ਰੀ ਅਕਾਲ" to "Punjabi",
    "नमस्कार" to "Marathi",
    "Hello" to "English",
)

/** A greeting that turns through the languages Boliyan speaks. */
@Composable
fun CyclingGreeting(modifier: Modifier = Modifier, animate: Boolean = true) {
    var index by remember { mutableIntStateOf(0) }
    if (animate) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(2_200)
                index = (index + 1) % greetings.size
            }
        }
    }

    AnimatedContent(
        targetState = index,
        transitionSpec = {
            (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
        },
        label = "greeting",
        modifier = modifier,
    ) { i ->
        val (word, language) = greetings[i]
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = word,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Text(
                text = language.uppercase(),
                style = overline,
            )
        }
    }
}
