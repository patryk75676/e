package pl.cyphr.app

import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Podpowiedz na pustym ekranie: etykieta i poczatek wiadomosci, ktory trafia do pola. */
private class Suggestion(val label: String, val prompt: String)

private val suggestions = listOf(
    Suggestion("Wyjaśnij prosto", "Wyjaśnij mi prosto, "),
    Suggestion("Napisz kod", "Napisz kod, który "),
    Suggestion("Streść tekst", "Streść ten tekst:\n"),
    Suggestion("Zaplanuj coś", "Pomóż mi zaplanować "),
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatTab(
    messages: List<ChatMessage>,
    agent: String?,
    thinking: Boolean,
    lastTokens: Pair<Int, Int>?,
    memory: Memory,
    chatTitle: String,
    chatId: String,
    onSend: (String) -> Unit,
    onPickAgent: () -> Unit,
    onOpenChats: () -> Unit,
    /** Usuwa wiadomosc o tym indeksie; zwraca, czy sie udalo. */
    onDelete: (Int) -> Boolean = { false },
) {
    // TextFieldValue, a nie String: po wstawieniu podpowiedzi kursor ma stac na koncu.
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    val input = remember { FocusRequester() }
    // Kazda rozmowa otwiera sie na dole, bez przewijania przez cala historie.
    val listState = remember(chatId) { LazyListState((messages.size - 1).coerceAtLeast(0)) }

    // Odpowiedzi od tego indeksu wzwyz jeszcze sie nie wypisaly. Pisze sie wylacznie to,
    // co doszlo po otwarciu rozmowy — historia z dysku, powrot na zakladke ani przejscie
    // do innej rozmowy nie przepisuja niczego od nowa. Decyzja zapada juz przy pierwszym
    // rysowaniu dymka: wczesniej zapadala klatke pozniej, gdy dymek mial juz caly tekst,
    // i pisanie konczylo sie, zanim sie zaczelo.
    var typed by remember(chatId) { mutableStateOf(messages.size) }
    // Wiadomosci, ktore juz byly w rozmowie przy jej otwarciu, pojawiaja sie bez animacji.
    var baseline by remember(chatId) { mutableStateOf(messages.size) }

    // Dymek z rozwinietymi akcjami (Kopiuj, Usun) — naraz tylko jeden.
    var actionsFor by remember(chatId) { mutableStateOf<Int?>(null) }
    // Wiadomosc czekajaca na potwierdzenie usuniecia, razem z trescia: usuwamy dokladnie
    // to, co bylo w oknie, nawet gdyby rozmowa w miedzyczasie sie zmienila.
    var confirmDelete by remember(chatId) { mutableStateOf<Pair<Int, ChatMessage>?>(null) }
    val keys = remember(messages) { messageKeys(messages) }

    // To, co faktycznie leci do modelu: notatka z zwinietej czesci plus swieze
    // wiadomosci. Liczone przy zmianie rozmowy, nie przy kazdym nacisnieciu klawisza.
    val contextTokens = remember(messages, memory) {
        estimateTokens(memory.summary) + freshOf(messages, memory).sumOf { estimateTokens(it.text) }
    }
    val folded = remember(messages, memory) { memory.folded.coerceAtMost(messages.size) }

    // Nowa wiadomosc przewija na dol. Usunieta — nie: usuwasz cos ze srodka historii
    // i zostajesz w tym miejscu.
    val seen = remember(chatId) { intArrayOf(messages.size) }
    LaunchedEffect(messages.size, thinking) {
        val removed = messages.size < seen[0]
        seen[0] = messages.size
        if (removed) return@LaunchedEffect
        val last = messages.size + if (thinking) 1 else 0
        if (last > 0) listState.animateScrollToItem(last - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 22.dp, top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenChats, modifier = Modifier.size(42.dp)) {
                Icon(
                    painterResource(R.drawable.ic_chat),
                    "Rozmowy",
                    tint = Paper,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            AnimatedContent(
                targetState = chatTitle,
                transitionSpec = { fadeIn(motionSpec(220)).togetherWith(fadeOut(motionSpec(140))) },
                label = "chatTitle",
                modifier = Modifier.weight(1f),
            ) { title ->
                Text(
                    title,
                    color = Paper,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            ModelChip(agent ?: "Wybierz model", onClick = onPickAgent)
        }

        // Licznik ma sens dopiero, gdy jest co liczyc — w pustej rozmowie to tylko szum.
        AnimatedVisibility(
            visible = messages.isNotEmpty() || draft.text.isNotEmpty(),
            enter = fadeIn(motionSpec(200)) + expandVertically(motionSpec(200)),
            exit = fadeOut(motionSpec(150)) + shrinkVertically(motionSpec(150)),
        ) {
            TokenBar(
                context = contextTokens,
                draft = estimateTokens(draft.text),
                last = lastTokens,
                folded = folded,
            )
        }

        AnimatedContent(
            targetState = messages.isEmpty() && !thinking,
            transitionSpec = { fadeIn(motionSpec(280)).togetherWith(fadeOut(motionSpec(160))) },
            label = "chatBody",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { empty ->
            if (empty) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Ghost(size = 96.dp, floating = true)
                        Spacer(Modifier.height(18.dp))
                        Text("O co zapytasz?", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Paper)
                        Spacer(Modifier.height(6.dp))
                        Lead(
                            agent?.let { "Odpowiada $it. Rozmowa liczy się z Twojego salda." }
                                ?: "Wybierz model, żeby zacząć.",
                            center = true,
                        )
                        Spacer(Modifier.height(22.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            suggestions.forEach { s ->
                                Text(
                                    s.label,
                                    color = Paper,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .border(1.5.dp, Line, RoundedCornerShape(50))
                                        .clickable {
                                            draft = TextFieldValue(s.prompt, TextRange(s.prompt.length))
                                            input.requestFocus()
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(messages, key = { i, _ -> keys[i] }) { i, message ->
                        Bubble(
                            message = message,
                            typing = !message.fromUser && i >= typed,
                            animate = i >= baseline,
                            onTyped = { if (typed <= i) typed = i + 1 },
                            actions = actionsFor == i,
                            // W trakcie odpowiedzi historia sie zapisuje — usuwanie poczeka.
                            canDelete = !thinking,
                            onToggleActions = { actionsFor = if (actionsFor == i) null else i },
                            onDelete = { actionsFor = null; confirmDelete = i to message },
                            // Po usunieciu reszta dymkow dosuwa sie plynnie, a nie skacze.
                            modifier = Modifier.animateItemPlacement(motionSpec(260)),
                        )
                    }
                    if (thinking) item(key = "typing") { TypingBubble(agent) }
                }
            }
        }

        val enabled = draft.text.isNotBlank() && !thinking
        val send = {
            if (enabled) {
                onSend(draft.text.trim())
                draft = TextFieldValue("")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Napisz wiadomość", color = Mist) },
                maxLines = 5,
                shape = RoundedCornerShape(26.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                // Klawiatura pokazuje „Wyslij” — bez tego przycisk nic nie robil.
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Raise, unfocusedContainerColor = Raise,
                    focusedTextColor = Paper, unfocusedTextColor = Paper,
                    cursorColor = Paper,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(input),
            )
            Spacer(Modifier.width(10.dp))
            val sendBg by animateColorAsState(if (enabled) Paper else Raise, motionSpec(180), label = "sendBg")
            val sendFg by animateColorAsState(if (enabled) Ink else Mist, motionSpec(180), label = "sendFg")
            val sendScale by animateFloatAsState(if (enabled) 1f else 0.9f, motionSpring(0.45f, 520f), label = "sendScale")
            Box(
                Modifier
                    .size(56.dp)
                    .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                    .clip(RoundedCornerShape(50))
                    .background(sendBg),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = send, enabled = enabled) {
                    Icon(
                        painterResource(R.drawable.ic_send),
                        contentDescription = "Wyślij",
                        tint = sendFg,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    confirmDelete?.let { (index, message) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Raise,
            title = { Text("Usunąć wiadomość?", color = Paper, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Zniknie z tej rozmowy i model przestanie ją widzieć. Odpowiedź modelu zostaje.",
                    color = Mist,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    // Tylko gdy pod tym miejscem wciaz jest ta sama wiadomosc.
                    if (messages.getOrNull(index) == message && onDelete(index)) {
                        // Dalsze wiadomosci przesuwaja sie o jedno miejsce — granice razem z nimi.
                        if (index < typed) typed -= 1
                        if (index < baseline) baseline -= 1
                    }
                }) {
                    Text("Usuń", color = Paper, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Zostaw", color = Mist) }
            },
        )
    }
}

/**
 * Klucze dymkow: ta sama wiadomosc zachowuje swoj dymek, gdy wczesniejsza zniknie.
 * Wiadomosci nie maja wlasnych numerow, wiec kluczem jest tresc, a powtorki tej samej
 * tresci dostaja kolejny numer.
 */
internal fun messageKeys(messages: List<ChatMessage>): List<String> {
    val seen = HashMap<String, Int>()
    return messages.map { m ->
        val base = (if (m.fromUser) "u" else "a") + m.text.hashCode()
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        "$base#$n"
    }
}

/** Wybrany model jako przycisk — dotkniecie otwiera liste modeli. */
@Composable
private fun ModelChip(name: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(1.5.dp, Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ghost(size = 16.dp)
        Spacer(Modifier.width(6.dp))
        AnimatedContent(
            targetState = name,
            transitionSpec = {
                (fadeIn(motionSpec(220)) + slideInVertically(motionSpec(220)) { it / 2 })
                    .togetherWith(fadeOut(motionSpec(140)) + slideOutVertically(motionSpec(140)) { -it / 2 })
            },
            label = "model",
        ) { n ->
            Text(n, color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Icon(
            painterResource(R.drawable.ic_chevron_down),
            contentDescription = "Zmień model",
            tint = Mist,
            modifier = Modifier.size(18.dp),
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    message: ChatMessage,
    typing: Boolean = false,
    animate: Boolean = true,
    onTyped: () -> Unit = {},
    actions: Boolean = false,
    canDelete: Boolean = true,
    onToggleActions: () -> Unit = {},
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val appear = remember { Animatable(if (animate && Prefs.animations) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, motionSpec(320)) }

    // Odpowiedz modelu odslania sie znak po znaku, od razu sformatowana — wczesniej
    // pisaly sie gole gwiazdki, ktore na koncu nagle znikaly. Cala tresc juz jest:
    // to efekt wizualny, nie strumieniowanie z serwera.
    val full = message.text
    val total = remember(full) {
        if (message.fromUser) full.length else Markdown.visibleLength(Markdown.blocks(full))
    }
    var shown by remember(message) { mutableStateOf(if (typing && Prefs.animations) 0 else total) }
    LaunchedEffect(typing) {
        if (!typing || !Prefs.animations) { shown = total; return@LaunchedEffect }
        val step = maxOf(1, total / 90)
        while (shown < total) {
            shown = minOf(total, shown + step)
            delay(16)
        }
        onTyped()
    }
    val caret = shown < total
    // Rog od strony nadawcy mniej zaokraglony — wiadomo, czyja to wypowiedz.
    val shape = if (message.fromUser) {
        RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    }
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.86f)
                .wrapContentWidth(if (message.fromUser) Alignment.End else Alignment.Start)
                .graphicsLayer {
                    // Dymek „wyrasta” z rogu nadawcy.
                    val k = appear.value
                    alpha = k
                    translationY = (1f - k) * 10f * density
                    scaleX = 0.94f + 0.06f * k
                    scaleY = 0.94f + 0.06f * k
                    transformOrigin = TransformOrigin(if (message.fromUser) 1f else 0f, 1f)
                }
                .clip(shape)
                .then(
                    if (message.fromUser) {
                        // Dotkniecie albo przytrzymanie wlasnej wiadomosci pokazuje jej akcje.
                        Modifier
                            .background(Paper)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = rememberRipple(color = Ink),
                                onClickLabel = "Pokaż opcje wiadomości",
                                onLongClick = onToggleActions,
                                onClick = onToggleActions,
                            )
                    } else {
                        Modifier.border(1.5.dp, Line, shape)
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (message.fromUser) {
                Text(full, color = Ink, fontSize = 15.sp)
            } else {
                // Przytrzymanie zaznacza fragment odpowiedzi; calosc kopiuje przycisk pod nia.
                SelectionContainer { ChatMarkdown(full, Paper, visible = shown, caret = caret) }
            }
        }
        if (message.fromUser) {
            AnimatedVisibility(
                visible = actions,
                enter = fadeIn(motionSpec(180)) + expandVertically(motionSpec(220), expandFrom = Alignment.Top),
                exit = fadeOut(motionSpec(140)) + shrinkVertically(motionSpec(180), shrinkTowards = Alignment.Top),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    CopyButton(full)
                    if (canDelete) ActionButton(R.drawable.ic_delete, "Usuń", onClick = onDelete)
                }
            }
        } else if (!caret) {
            CopyButton(full)
        }
    }
}

/** Mala akcja pod dymkiem: ikona i podpis, w stylu przycisku „Kopiuj”. */
@Composable
private fun ActionButton(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Mist, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Mist, fontSize = 12.sp)
    }
}

/** Cala odpowiedz do schowka jednym dotknieciem — zaznaczanie dlugiego tekstu palcem jest meczace. */
@Composable
private fun CopyButton(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1600); copied = false }
    }
    Row(
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(50))
            .clickable { clipboard.setText(AnnotatedString(text)); copied = true }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = { fadeIn(motionSpec(160)).togetherWith(fadeOut(motionSpec(120))) },
            label = "copy",
        ) { done ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(if (done) R.drawable.ic_check else R.drawable.ic_copy),
                    contentDescription = null,
                    tint = Mist,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (done) "Skopiowano" else "Kopiuj", color = Mist, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Odpowiedz modelu z Markdownem: pogrubienia, kursywa, `kod` w linii, naglowki,
 * punktory i bloki kodu przewijane w poziomie, zeby dlugie linie sie nie lamaly.
 * [visible] to liczba widocznych znakow (bez znacznikow) — przy pisaniu odpowiedzi
 * formatowanie jest od poczatku to samo, przybywa tylko tekstu.
 */
@Composable
fun ChatMarkdown(text: String, color: Color, visible: Int = Int.MAX_VALUE, caret: Boolean = false) {
    val blocks = remember(text) { Markdown.blocks(text) }
    // Ile znakow kazdego bloku widac. Bloki za granica jeszcze sie nie rysuja.
    val parts = remember(blocks, visible) {
        var budget = visible
        blocks.mapNotNull { block ->
            if (budget <= 0) return@mapNotNull null
            val length = Markdown.visibleLength(listOf(block))
            val take = minOf(length, budget)
            budget -= length
            block to take
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { i, (block, take) ->
            val cursor = if (caret && i == parts.lastIndex) "▌" else ""
            key(i) {
                when (block) {
                    is Markdown.Block.Text ->
                        Text(styled(block.spans, take) + AnnotatedString(cursor), color = color, fontSize = 15.sp)
                    is Markdown.Block.Code -> Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Raise)
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(
                            block.code.take(take) + cursor,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/** Fragmenty ze stylami, obciete do [limit] widocznych znakow. */
private fun styled(spans: List<Markdown.Span>, limit: Int = Int.MAX_VALUE): AnnotatedString = buildAnnotatedString {
    var left = limit
    for (span in spans) {
        if (left <= 0) break
        val part = if (span.text.length > left) span.text.take(left) else span.text
        left -= span.text.length
        when (span.style) {
            Markdown.Style.Plain -> append(part)
            Markdown.Style.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
            Markdown.Style.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(part) }
            Markdown.Style.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Raise)) {
                append(part)
            }
        }
    }
}

@Composable
private fun TypingBubble(agent: String?) {
    val still = !Prefs.animations
    val anim = rememberInfiniteTransition(label = "typing")
    val shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val appear = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, motionSpec(260)) }
    Row(
        Modifier.graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 8f * density },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(shape)
                .border(1.5.dp, Line, shape)
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
        if (agent != null) {
            Spacer(Modifier.width(12.dp))
            Text("$agent myśli…", color = Mist, fontSize = 13.sp)
        }
    }
}
