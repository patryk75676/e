package pl.cyphr.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Karta platnicza rysowana w Compose. Obraca sie w trzech osiach,
 * ma refleks swiatla, chip i pasek magnetyczny na rewersie.
 */
@Composable
fun CreditCard3D(
    label: String,
    amount: String,
    holder: String = "",
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    idle: Boolean = true,
) {
    val moving = idle && Prefs.animations
    val anim = rememberInfiniteTransition(label = "card")
    val swayRaw by anim.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sway",
    )
    val sway = if (moving) swayRaw else 0f
    val flip by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = motionSpec(700),
        label = "flip",
    )
    val tiltX = sway * 6f
    val tiltY = sway * 12f + flip

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.58f)
            .graphicsLayer {
                cameraDistance = 14f * density
                rotationX = tiltX
                rotationY = tiltY
                shadowElevation = 18f * density
                shape = RoundedCornerShape(22.dp)
                clip = true
            }
            .background(Brush.linearGradient(listOf(Color(0xFF1B1B1B), Color(0xFF090909)))),
    ) {
        // Refleks swiatla przesuwajacy sie po karcie
        Canvas(Modifier.fillMaxSize()) {
            val shift = (sway + 1f) / 2f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent),
                    start = Offset(size.width * (shift - 0.4f), 0f),
                    end = Offset(size.width * (shift + 0.4f), size.height),
                ),
                size = Size(size.width, size.height),
            )
        }

        if (flip < 90f) CardFront(label, amount, holder) else CardBack()
    }
}

@Composable
private fun CardFront(label: String, amount: String, holder: String) {
    Column(
        Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("CYPHR", color = Paper, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                Text(label, color = Mist, fontSize = 13.sp)
            }
            Ghost(size = 36.dp)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip()
            Spacer(Modifier.width(14.dp))
            Text("••••  ••••  ••••  4242", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column {
                Text("KWOTA", color = Mist, fontSize = 11.sp)
                Text(amount, color = Paper, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (holder.isNotBlank()) {
                Text(
                    holder.uppercase(),
                    color = Paper,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
        }
    }
}

@Composable
private fun CardBack() {
    Column(
        Modifier.fillMaxSize().graphicsLayer { rotationY = 180f },
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(46.dp).background(Color(0xFF050505)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(Paper).padding(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("CVV ***", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
        Text(
            "Płatność testowa. Żadne prawdziwe środki nie są pobierane.",
            color = Mist, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
    }
}

@Composable
private fun Chip() {
    Box(
        Modifier
            .size(44.dp, 34.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFE8E8E8), Color(0xFF9A9A9A)))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = size.height / 3f
            for (i in 1..2) {
                drawLine(Color(0x33000000), Offset(0f, step * i), Offset(size.width, step * i), strokeWidth = 1.5f)
            }
            drawLine(Color(0x33000000), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 1.5f)
        }
    }
}

/** Animacja zakupu: karta wsuwa sie w czytnik, obraca i konczy znakiem potwierdzenia. */
@Composable
fun BuyAnimation(label: String, amount: String, holder: String, done: Boolean, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, motionSpec(900)) }
    val check by animateFloatAsState(if (done) 1f else 0f, motionSpec(500), label = "check")

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CreditCard3D(
            label = label,
            amount = amount,
            holder = holder,
            flipped = done,
            idle = !done,
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .graphicsLayer {
                    val p = progress.value
                    translationY = (1f - p) * 60f * density
                    alpha = p
                    scaleX = 0.92f + 0.08f * p - 0.06f * check
                    scaleY = 0.92f + 0.08f * p - 0.06f * check
                },
        )
        if (check > 0f) {
            Box(
                Modifier
                    .size(96.dp)
                    .graphicsLayer { alpha = check; scaleX = 0.6f + 0.4f * check; scaleY = 0.6f + 0.4f * check }
                    .clip(RoundedCornerShape(50))
                    .background(Paper),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Ink, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}
