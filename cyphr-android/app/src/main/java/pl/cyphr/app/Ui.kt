package pl.cyphr.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

val Ink = Color(0xFF000000)
val Paper = Color(0xFFFFFFFF)
val Mist = Color(0xFF8E8E8E)
val Line = Color(0xFF2E2E2E)
val Raise = Color(0xFF161616)

private val cyphrColors = darkColorScheme(
    primary = Paper, onPrimary = Ink,
    background = Ink, onBackground = Paper,
    surface = Ink, onSurface = Paper,
    surfaceVariant = Raise, onSurfaceVariant = Mist,
    outline = Line, error = Paper, onError = Ink,
)

@Composable
fun CyphrTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = cyphrColors, content = content)

/** Czas trwania animacji. Gdy ruch jest wylaczony, wszystko dzieje sie natychmiast. */
fun motion(millis: Int): Int = if (Prefs.animations) millis else 0

fun <T> motionSpec(millis: Int, easing: Easing = FastOutSlowInEasing): FiniteAnimationSpec<T> =
    if (Prefs.animations) tween(millis, easing = easing) else snap()

fun usd(value: Double): String {
    val digits = if (value > 0 && value < 0.01) 4 else 2
    return String.format(Locale("pl", "PL"), "%,.${digits}f USD", value)
}

fun int(value: Long): String = String.format(Locale("pl", "PL"), "%,d", value)

/** Logo. Unosi sie w gorze i w dole, gdy floating = true. */
@Composable
fun Ghost(size: Dp, modifier: Modifier = Modifier, floating: Boolean = false) {
    val shift: Float
    val tilt: Float
    if (floating && Prefs.animations) {
        val anim = rememberInfiniteTransition(label = "ghost")
        val k by anim.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2100, easing = LinearEasing), RepeatMode.Reverse),
            label = "float",
        )
        shift = -10f * k
        tilt = -1.5f + 3f * k
    } else {
        shift = 0f; tilt = 0f
    }
    Icon(
        painter = painterResource(R.drawable.ghost),
        contentDescription = null,
        tint = Paper,
        modifier = modifier
            .size(size)
            .graphicsLayer { translationY = shift * density; rotationZ = tilt },
    )
}

@Composable
private fun GhostSpinner() {
    if (!Prefs.animations) {
        Icon(
            painter = painterResource(R.drawable.ghost),
            contentDescription = "Czekaj",
            tint = LocalContentColor.current,
            modifier = Modifier.size(24.dp),
        )
        return
    }
    val anim = rememberInfiniteTransition(label = "spin")
    val k by anim.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    Icon(
        painter = painterResource(R.drawable.ghost),
        contentDescription = "Czekaj",
        tint = LocalContentColor.current,
        modifier = Modifier.size(24.dp).graphicsLayer { translationY = (3f - 6f * k) * density },
    )
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = CyphrButton(text, modifier, busy, enabled, filled = true, onClick = onClick)

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = CyphrButton(text, modifier, busy, enabled, filled = false, onClick = onClick)

@Composable
private fun CyphrButton(
    text: String,
    modifier: Modifier,
    busy: Boolean,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, motionSpec(120), label = "press")
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        interactionSource = source,
        shape = RoundedCornerShape(50),
        border = if (filled) null else BorderStroke(1.5.dp, Line),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) Paper else Color.Transparent,
            contentColor = if (filled) Ink else Paper,
            disabledContainerColor = if (filled) Paper.copy(alpha = 0.45f) else Color.Transparent,
            disabledContentColor = if (filled) Ink else Mist,
        ),
        modifier = modifier.fillMaxWidth().height(54.dp).scale(scale),
    ) {
        if (busy) GhostSpinner()
        else Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Field(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    lines: Int = 1,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Mist, fontSize = 14.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = lines == 1,
            shape = RoundedCornerShape(14.dp),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Paper, unfocusedBorderColor = Line,
                focusedTextColor = Paper, unfocusedTextColor = Paper,
                cursorColor = Paper,
                focusedContainerColor = Ink, unfocusedContainerColor = Ink,
            ),
            modifier = Modifier.fillMaxWidth().height(if (lines == 1) 56.dp else (28 * lines + 28).dp),
        )
    }
}

@Composable
fun ErrorText(message: String?) {
    if (message.isNullOrBlank()) return
    val shake = remember(message) { Animatable(0f) }
    LaunchedEffect(message) {
        if (Prefs.animations) {
            shake.animateTo(1f, tween(400, easing = LinearEasing))
            shake.snapTo(0f)
        }
    }
    Text(
        "! $message",
        color = Paper,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = (kotlin.math.sin(shake.value * 12f) * 5f) * density },
    )
}

@Composable
fun Segmented(register: Boolean, onChange: (Boolean) -> Unit) {
    val offset by animateFloatAsState(if (register) 1f else 0f, motionSpec(400), label = "seg")
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Ink, RoundedCornerShape(50))
            .border(1.5.dp, Line, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.5f)
                .fillMaxHeight()
                .graphicsLayer { translationX = offset * size.width }
                .background(Paper, RoundedCornerShape(50)),
        )
        Row(Modifier.fillMaxSize()) {
            SegLabel("Logowanie", !register, Modifier.weight(1f)) { onChange(false) }
            SegLabel("Nowe konto", register, Modifier.weight(1f)) { onChange(true) }
        }
    }
}

@Composable
private fun SegLabel(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color by animateColorAsState(if (on) Ink else Mist, motionSpec(280), label = "segText")
    TextButton(onClick = onClick, modifier = modifier.fillMaxHeight()) {
        Text(text, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Paper)

@Composable
fun Lead(text: String, modifier: Modifier = Modifier, center: Boolean = false) =
    Text(
        text,
        modifier = modifier,
        color = Mist,
        fontSize = 15.sp,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
    )

@Composable
fun Divider(text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.weight(1f).height(1.dp).background(Line))
    Text(text, color = Mist, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp))
    Box(Modifier.weight(1f).height(1.dp).background(Line))
}

/** Pytanie o zgodę na działanie: jednorazowe, na stałe albo odmowa. */
@Composable
fun PermissionDialog(
    title: String,
    what: String,
    detail: String?,
    allowAlways: Boolean = true,
    onAllowOnce: () -> Unit,
    onAllowAlways: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        containerColor = Raise,
        titleContentColor = Paper,
        textContentColor = Mist,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(what, color = Paper, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 14.sp)
                if (detail != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(detail, color = Mist, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            Row {
                if (allowAlways) {
                    TextButton(onClick = onAllowAlways) { Text("Zawsze", color = Mist) }
                }
                TextButton(onClick = onAllowOnce) { Text("Zezwól", color = Paper, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = onDeny) { Text("Odrzuć", color = Mist) } },
    )
}
