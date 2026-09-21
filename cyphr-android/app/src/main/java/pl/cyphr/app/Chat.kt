package pl.cyphr.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatTab(
    messages: List<ChatMessage>,
    agent: String?,
    thinking: Boolean,
    lastTokens: Pair<Int, Int>?,
    memory: Memory,
    chatTitle: String,
    onSend: (String) -> Unit,
    onPickAgent: () -> Unit,
    onOpenChats: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Indeks wiadomosci, ktora ma sie dopiero wypisac. -1 = nic sie nie pisze.
    // Pisze sie wylacznie odpowiedz, ktora wlasnie doszla — historia wczytana
    // z dysku ani powrot na zakladke nie przepisuja niczego od nowa.
    var seen by remember { mutableStateOf(messages.size) }
    var typeAt by remember { mutableStateOf(-1) }
    LaunchedEffect(messages.size) {
        if (messages.size > seen) {
            val last = messages.lastOrNull()
            if (last != null && !last.fromUser) typeAt = messages.lastIndex
        }
        seen = messages.size
    }

    // To, co faktycznie leci do modelu: notatka z zwinietej czesci plus swieze
    // wiadomosci. Liczone przy zmianie rozmowy, nie przy kazdym nacisnieciu klawisza.
    val contextTokens = remember(messages, memory) {
        estimateTokens(memory.summary) + freshOf(messages, memory).sumOf { estimateTokens(it.text) }
    }
    val folded = remember(messages, memory) { memory.folded.coerceAtMost(messages.size) }

    LaunchedEffect(messages.size, thinking) {
        val last = messages.size + if (thinking) 1 else 0
        if (last > 0) listState.animateScrollToItem(last - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenChats, modifier = Modifier.size(34.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_chat),
                        "Rozmowy",
                        tint = Paper,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        chatTitle,
                        color = Paper,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        agent ?: "Nie wybrano agenta",
                        color = Mist,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onPickAgent) { Text("Zmień", color = Mist) }
        }

        TokenBar(
            context = contextTokens,
            draft = estimateTokens(draft),
            last = lastTokens,
            folded = folded,
        )

        if (messages.isEmpty() && !thinking) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Ghost(size = 110.dp, floating = true)
                Spacer(Modifier.height(20.dp))
                Text("O co zapytasz?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Paper)
                Spacer(Modifier.height(6.dp))
                Lead("Rozmowa liczy się z Twojego salda.", center = true)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(messages) { i, message ->
                    Bubble(
                        message = message,
                        typing = i == typeAt,
                        onTyped = { if (typeAt == i) typeAt = -1 },
                    )
                }
                if (thinking) item { TypingBubble() }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Napisz wiadomość", color = Mist) },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Raise, unfocusedContainerColor = Raise,
                    focusedTextColor = Paper, unfocusedTextColor = Paper,
                    cursorColor = Paper,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            val enabled = draft.isNotBlank() && !thinking
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (enabled) Paper else Raise),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = { onSend(draft.trim()); draft = "" },
                    enabled = enabled,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_send),
                        contentDescription = "Wyślij",
                        tint = if (enabled) Ink else Mist,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Licznik tokenow nad polem wpisywania. Kontekst i szkic sa szacowane na telefonie,
 * bo prawdziwy tokenizer siedzi po stronie modelu. Liczby po wymianie sa juz dokladne —
 * przychodza w polu "usage" odpowiedzi.
 */
@Composable
private fun TokenBar(context: Int, draft: Int, last: Pair<Int, Int>?, folded: Int) {
    val total = context + draft
    val grow by animateFloatAsState(if (draft > 0) 1f else 0.55f, motionSpec(220), label = "tok")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "≈ $total tok. w zapytaniu" +
                (if (draft > 0) "  (+$draft)" else "") +
                (if (folded > 0) "  ·  $folded zwinięte" else ""),
            color = Mist,
            fontSize = 12.sp,
            modifier = Modifier.graphicsLayer { alpha = grow },
        )
        if (last != null) {
            Text("ostatnia: ${last.first} → ${last.second}", color = Mist, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage, typing: Boolean = false, onTyped: () -> Unit = {}) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, motionSpec(320)) }

    // Odpowiedz modelu odslania sie znak po znaku. Cala tresc juz jest —
    // to wylacznie efekt wizualny, nie strumieniowanie z serwera.
    val full = message.text
    var shown by remember(message) { mutableStateOf(if (typing && Prefs.animations) 0 else full.length) }
    LaunchedEffect(typing) {
        if (!typing || !Prefs.animations) { shown = full.length; return@LaunchedEffect }
        val step = maxOf(1, full.length / 90)
        while (shown < full.length) {
            shown = minOf(full.length, shown + step)
            delay(16)
        }
        onTyped()
    }
    val caret = shown < full.length
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.86f)
                .wrapContentWidth(if (message.fromUser) Alignment.End else Alignment.Start)
                .graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * 12f * density
                }
                .clip(RoundedCornerShape(20.dp))
                .then(
                    if (message.fromUser) Modifier.background(Paper)
                    else Modifier.border(1.5.dp, Line, RoundedCornerShape(20.dp))
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                full.take(shown) + if (caret) "▌" else "",
                color = if (message.fromUser) Ink else Paper,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun TypingBubble() {
    val still = !Prefs.animations
    val anim = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.5.dp, Line, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val kRaw by anim.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(600, delayMillis = index * 150, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            val k = if (still) 0.6f else kRaw
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer { translationY = -4f * k * density; alpha = 0.4f + 0.6f * k }
                    .clip(RoundedCornerShape(50))
                    .background(Paper),
            )
        }
    }
}
