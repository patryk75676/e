package pl.cyphr.app

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Pole wpisywania poza ekranem: zalaczniki czekajace na wyslanie i tekst udostepniony
 * z innej aplikacji. Poza ekranem, bo „Udostepnij → CYPHR” moze przyjsc, gdy aplikacja
 * jest zablokowana albo na innym ekranie — nic przy tym nie jest wysylane, tylko czeka.
 */
object Composer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _staged = MutableStateFlow<List<Attachment>>(emptyList())
    val staged: StateFlow<List<Attachment>> = _staged

    /** Ile plikow wlasnie sie wczytuje — kafelki z kolkiem. */
    private val _importing = MutableStateFlow(0)
    val importing: StateFlow<Int> = _importing

    /** Komunikaty dla uzytkownika: za duzy plik, zly typ, limit zalacznikow. */
    private val _problems = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val problems: SharedFlow<String> = _problems

    /** Tekst udostepniony z innej aplikacji — trafia do pola wpisywania. */
    private val _sharedText = MutableStateFlow<String?>(null)
    val sharedText: StateFlow<String?> = _sharedText

    /** Cos przyszlo z „Udostepnij” — ekran przechodzi do czatu. */
    private val _arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val arrivals: SharedFlow<Unit> = _arrivals

    fun add(context: Context, uris: List<Uri>, fromShare: Boolean = false) {
        if (uris.isEmpty()) return
        val app = context.applicationContext
        val free = Attachments.MAX_PER_MESSAGE - _staged.value.size - _importing.value
        if (free <= 0) {
            _problems.tryEmit(tr("Najwyżej ${Attachments.MAX_PER_MESSAGE} załączniki w jednej wiadomości.", "At most ${Attachments.MAX_PER_MESSAGE} attachments in one message."))
            return
        }
        val take = uris.take(free)
        if (uris.size > free) {
            _problems.tryEmit(tr("Dodano ${take.size} — najwyżej ${Attachments.MAX_PER_MESSAGE} w jednej wiadomości.", "Added ${take.size} — at most ${Attachments.MAX_PER_MESSAGE} in one message."))
        }
        if (fromShare) _arrivals.tryEmit(Unit)
        for (uri in take) {
            _importing.value += 1
            scope.launch {
                try {
                    val a = Attachments.import(app, uri)
                    _staged.value = _staged.value + a
                } catch (e: AttachError) {
                    _problems.tryEmit(e.message ?: tr("Nie udało się dodać pliku.", "Couldn't add the file."))
                } catch (e: Exception) {
                    _problems.tryEmit(tr("Nie udało się dodać pliku.", "Couldn't add the file."))
                } finally {
                    _importing.value -= 1
                }
            }
        }
    }

    fun remove(context: Context, a: Attachment) {
        _staged.value = _staged.value - a
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) { Attachments.delete(app, listOf(a)) }
    }

    /** Zalaczniki do wyslania — od tej chwili naleza do wiadomosci. */
    fun take(): List<Attachment> {
        val s = _staged.value
        _staged.value = emptyList()
        return s
    }

    fun share(text: String) {
        _sharedText.value = text
        _arrivals.tryEmit(Unit)
    }

    fun consumeText(): String? = _sharedText.value.also { _sharedText.value = null }

    /**
     * Wiadomosci z kolejki wracaja do pola po „Stop”: tekst do wpisywanego, zalaczniki na
     * wolne miejsca. Co sie nie miesci, jest usuwane — to kopie, oryginaly zostaja w telefonie.
     * Zwraca, ile zalacznikow sie nie zmiescilo.
     */
    fun restore(context: Context, text: String, attachments: List<Attachment>): Int {
        val free = (Attachments.MAX_PER_MESSAGE - _staged.value.size - _importing.value).coerceAtLeast(0)
        val keep = attachments.take(free)
        val drop = attachments.drop(free)
        if (keep.isNotEmpty()) _staged.value = _staged.value + keep
        if (drop.isNotEmpty()) {
            val app = context.applicationContext
            scope.launch(Dispatchers.IO) { Attachments.delete(app, drop) }
        }
        if (text.isNotBlank()) _sharedText.value = listOfNotNull(_sharedText.value, text).joinToString("\n")
        return drop.size
    }

    /** Obraz ze schowka, dla ktorego podpowiedz „Wklej” juz zniknela (wklejony albo schowany). */
    var clipHandled by mutableStateOf<Long?>(null)

    /** Obraz ze schowka, jesli tam jest — np. skopiowany zrzut ekranu. Samo sprawdzenie niczego nie czyta. */
    fun clipboardImage(context: Context): Long? = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val d = cm.primaryClipDescription
        if (d != null && (0 until d.mimeTypeCount).any { d.getMimeType(it).startsWith("image/") }) d.timestamp else null
    } catch (_: Exception) {
        null
    }

    fun pasteImage(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = try { cm.primaryClip } catch (_: Exception) { null }
        val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
        if (uris.isEmpty()) _problems.tryEmit(tr("W schowku nie ma obrazu.", "There's no image on the clipboard.")) else add(context, uris)
    }
}

// ------------------------------------------------------------------------------------------
// Pole wpisywania: przycisk „+”, menu i kafelki zalacznikow
// ------------------------------------------------------------------------------------------

/** Okragly przycisk „+” — obraca sie w „x”, gdy menu jest otwarte. */
@Composable
fun AttachButton(open: Boolean, onClick: () -> Unit) {
    val turn by animateFloatAsStateCompat(if (open) 45f else 0f)
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(50))
            .background(Raise)
            .clickable(onClickLabel = tr("Dodaj zdjęcie lub plik", "Add a photo or file"), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_plus),
            contentDescription = tr("Dodaj zdjęcie lub plik", "Add a photo or file"),
            tint = Paper,
            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = turn },
        )
    }
}

@Composable
private fun animateFloatAsStateCompat(target: Float): State<Float> =
    androidx.compose.animation.core.animateFloatAsState(target, motionSpring(0.6f, 500f), label = "attachTurn")

/** Menu pod „+”. Pozycje, ktorych teraz nie ma sensu pokazywac (schowek bez obrazu), znikaja. */
@Composable
fun AttachMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onPaste: (() -> Unit)?,
    images: ImageQuota?,
    onCreateImage: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(Raise).border(1.dp, Line, RoundedCornerShape(12.dp)),
    ) {
        MenuRow(R.drawable.ic_image, tr("Zdjęcie lub zrzut ekranu", "Photo or screenshot"), null) { onDismiss(); onPhotos() }
        MenuRow(R.drawable.ic_file, tr("Plik", "File"), tr("PDF, tekst, kod", "PDF, text, code")) { onDismiss(); onFiles() }
        if (onPaste != null) MenuRow(R.drawable.ic_paste, tr("Wklej obraz ze schowka", "Paste image from clipboard"), null) { onDismiss(); onPaste() }
        if (images != null) {
            MenuRow(
                R.drawable.ic_sparkle,
                tr("Stwórz obraz", "Create an image"),
                if (images.left > 0) tr("Dziś jeszcze ${images.left} z ${images.limit}", "${images.left} of ${images.limit} left today")
                else tr("Limit na dziś wykorzystany", "Today's limit is used up"),
                enabled = images.left > 0,
            ) { onDismiss(); onCreateImage() }
        }
    }
}

@Composable
private fun MenuRow(icon: Int, label: String, hint: String?, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        enabled = enabled,
        leadingIcon = {
            Icon(painterResource(icon), null, tint = if (enabled) Paper else Mist, modifier = Modifier.size(20.dp))
        },
        text = {
            Column {
                Text(label, color = if (enabled) Paper else Mist, fontSize = 15.sp)
                if (hint != null) Text(hint, color = Mist, fontSize = 12.sp)
            }
        },
        colors = MenuDefaults.itemColors(),
        onClick = onClick,
    )
}

/** Zalaczniki czekajace w polu wpisywania: miniatury z „x” i kafelki wczytywania. */
@Composable
fun StagedStrip(items: List<Attachment>, importing: Int, onRemove: (Attachment) -> Unit, onOpen: (Attachment) -> Unit) {
    AnimatedVisibility(
        visible = items.isNotEmpty() || importing > 0,
        enter = fadeIn(motionSpec(180)),
        exit = fadeOut(motionSpec(140)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { a -> key(a.files.firstOrNull() ?: a.name + a.size) { StagedTile(a, { onRemove(a) }, { onOpen(a) }) } }
            repeat(importing) {
                Box(
                    Modifier.size(68.dp).clip(RoundedCornerShape(14.dp)).background(Raise),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = Paper, strokeWidth = 2.dp, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}

@Composable
private fun StagedTile(a: Attachment, onRemove: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val appear = remember { Animatable(if (Prefs.animations) 0.85f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, motionSpring(0.55f, 420f)) }
    Box(Modifier.graphicsLayer { scaleX = appear.value; scaleY = appear.value }) {
        val shape = RoundedCornerShape(14.dp)
        if (a.kind == Attachment.Kind.Text) {
            Column(
                Modifier.size(width = 132.dp, height = 68.dp).clip(shape).background(Raise).border(1.dp, Line, shape)
                    .padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_file), null, tint = Paper, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(a.name, color = Paper, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(Attachments.humanSize(a.size), color = Mist, fontSize = 11.sp)
            }
        } else {
            Box(Modifier.size(68.dp).clip(shape).background(Raise).clickable(onClick = onOpen)) {
                AsyncImage(
                    model = a.files.firstOrNull()?.let { Attachments.file(context, it) },
                    contentDescription = a.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (a.kind == Attachment.Kind.Pdf) Badge("PDF", Modifier.align(Alignment.BottomStart).padding(5.dp))
            }
        }
        // „x” w rogu — zalacznik wypada z wiadomosci i znika z telefonu. Kolko jest male,
        // ale pole dotyku wieksze, zeby trafic palcem.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 8.dp, y = (-8).dp)
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClickLabel = tr("Usuń załącznik", "Remove attachment"), onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(Paper), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_close), tr("Usuń załącznik", "Remove attachment"), tint = Ink, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Ink,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Paper).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** Podpowiedz nad polem, gdy w schowku jest obraz (np. skopiowany zrzut ekranu). */
@Composable
fun PasteChip(visible: Boolean, onPaste: () -> Unit, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motionSpec(200)) + scaleIn(motionSpec(200), initialScale = 0.9f),
        exit = fadeOut(motionSpec(140)) + scaleOut(motionSpec(140), targetScale = 0.9f),
    ) {
        Row(
            Modifier
                .padding(start = 16.dp, top = 6.dp)
                .clip(RoundedCornerShape(50))
                .border(1.5.dp, Line, RoundedCornerShape(50))
                .clickable(onClick = onPaste)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_paste), null, tint = Paper, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("Wklej obraz ze schowka", "Paste image from clipboard"), color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(2.dp))
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(50)).clickable(onClickLabel = tr("Schowaj", "Hide"), onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_close), tr("Schowaj", "Hide"), tint = Mist, modifier = Modifier.size(12.dp)) }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Zalaczniki w dymkach rozmowy
// ------------------------------------------------------------------------------------------

/**
 * Zalaczniki wiadomosci. Zdjecia uzytkownika jako kafelki (jedno — wieksze), pliki jako
 * karty, obraz stworzony przez model — duzy, z „Zapisz” i „Udostepnij” pod spodem.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageMedia(
    attachments: List<Attachment>,
    fromUser: Boolean,
    onOpen: (Attachment, Int) -> Unit,
    onLongPress: () -> Unit = {},
    onNote: (String) -> Unit = {},
) {
    if (attachments.isEmpty()) return
    val context = LocalContext.current
    val photos = attachments.filter { it.kind == Attachment.Kind.Image }
    val cards = attachments.filter { it.kind == Attachment.Kind.Pdf || it.kind == Attachment.Kind.Text }
    val generated = attachments.filter { it.kind == Attachment.Kind.Generated }
    Column(
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (photos.size == 1) {
            val a = photos.first()
            val ratio = if (a.width > 0 && a.height > 0) a.width.toFloat() / a.height else 1f
            AsyncImage(
                model = a.files.firstOrNull()?.let { Attachments.file(context, it) },
                contentDescription = a.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .widthIn(max = 250.dp)
                    .heightIn(max = 320.dp)
                    .aspectRatio(ratio.coerceIn(0.5f, 2f))
                    .clip(RoundedCornerShape(18.dp))
                    .background(Raise)
                    .combinedClickable(onLongClick = onLongPress, onClick = { onOpen(a, 0) }),
            )
        } else if (photos.size > 1) {
            // Dwie kolumny kwadratow — jak w komunikatorach.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                photos.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { a ->
                            AsyncImage(
                                model = a.files.firstOrNull()?.let { Attachments.file(context, it) },
                                contentDescription = a.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Raise)
                                    .combinedClickable(onLongClick = onLongPress, onClick = { onOpen(a, 0) }),
                            )
                        }
                    }
                }
            }
        }
        cards.forEach { a -> FileCard(a, onClick = { if (a.kind == Attachment.Kind.Pdf) onOpen(a, 0) }, onLongPress = onLongPress) }
        generated.forEach { a -> GeneratedImage(a, onOpen = { onOpen(a, 0) }, onNote = onNote) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(a: Attachment, onClick: () -> Unit, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .widthIn(max = 280.dp)
            .clip(shape)
            .border(1.5.dp, Line, shape)
            .combinedClickable(onLongClick = onLongPress, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Raise),
            contentAlignment = Alignment.Center,
        ) {
            if (a.kind == Attachment.Kind.Pdf && a.files.isNotEmpty()) {
                AsyncImage(
                    model = Attachments.file(context, a.files.first()),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(painterResource(R.drawable.ic_file), null, tint = Paper, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(a.name, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = when (a.kind) {
                Attachment.Kind.Pdf -> "PDF · " + pagesLabel(a.pages) + " · " + Attachments.humanSize(a.size)
                else -> Attachments.humanSize(a.size) + (if (a.truncated) tr(" · model widzi początek", " · the model sees the beginning") else "")
            }
            Text(detail, color = Mist, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** „1 strona”, „3 strony”, „12 stron” — albo „1 page”, „3 pages”. */
internal fun pagesLabel(n: Int): String = count(n, "strona", "strony", "stron", "page", "pages")

/** Obraz od modelu: duzy, z pojawieniem sie i akcjami pod spodem. */
@Composable
private fun GeneratedImage(a: Attachment, onOpen: () -> Unit, onNote: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val path = a.files.firstOrNull() ?: return
    val ratio = if (a.width > 0 && a.height > 0) a.width.toFloat() / a.height else 1f
    val appear = remember { Animatable(if (Prefs.animations) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, motionSpec(420)) }
    Column {
        AsyncImage(
            model = Attachments.file(context, path),
            contentDescription = a.prompt.ifBlank { tr("Obraz stworzony przez model", "Image created by the model") },
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .aspectRatio(ratio.coerceIn(0.5f, 2f))
                .graphicsLayer {
                    alpha = appear.value
                    val k = 0.96f + 0.04f * appear.value
                    scaleX = k; scaleY = k
                }
                .clip(RoundedCornerShape(18.dp))
                .background(Raise)
                .clickable(onClickLabel = tr("Powiększ", "Enlarge"), onClick = onOpen),
        )
        Row(Modifier.padding(top = 2.dp)) {
            if (Attachments.canSaveToGallery) {
                MediaAction(R.drawable.ic_download, tr("Zapisz", "Save")) {
                    scope.launch {
                        onNote(
                            if (Attachments.saveToGallery(context, path, a.mime)) tr("Zapisano w galerii (Obrazy/CYPHR).", "Saved to the gallery (Pictures/CYPHR).")
                            else tr("Nie udało się zapisać obrazu.", "Couldn't save the image."),
                        )
                    }
                }
            }
            MediaAction(R.drawable.ic_share, tr("Udostępnij", "Share")) {
                try { context.startActivity(Attachments.shareIntent(context, path, a.mime)) } catch (_: Exception) {
                    onNote(tr("Nie udało się udostępnić obrazu.", "Couldn't share the image."))
                }
            }
        }
    }
}

@Composable
private fun MediaAction(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = Mist, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Mist, fontSize = 12.sp)
    }
}

/** Obraz w trakcie tworzenia: kwadrat z przesuwajacym sie blaskiem i podpisem. */
@Composable
fun DrawingBubble() {
    val still = !Prefs.animations
    val t = rememberInfiniteTransition(label = "drawing")
    val shift by t.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shine",
    )
    val x = if (still) 0.5f else shift
    // Blask liczony od rozmiaru kafelka w pikselach danego ekranu.
    val side = with(androidx.compose.ui.platform.LocalDensity.current) { 220.dp.toPx() }
    Column {
        Box(
            Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Raise)
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color.Transparent, Paper.copy(alpha = 0.10f), Color.Transparent),
                        start = Offset(x * side - side / 3f, 0f),
                        end = Offset(x * side + side / 3f, side),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_sparkle), null, tint = Mist, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(tr("Tworzę obraz…", "Creating the image…"), color = Mist, fontSize = 13.sp)
    }
}

// ------------------------------------------------------------------------------------------
// Podglad na pelnym ekranie
// ------------------------------------------------------------------------------------------

/**
 * Obraz na calym ekranie: dwa palce powiekszaja i przesuwaja, dwukrotne dotkniecie
 * wraca do calosci. PDF — kolejne strony pod soba.
 */
@Composable
fun ImageViewer(a: Attachment, onDismiss: () -> Unit, onNote: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Ink)) {
            if (a.kind == Attachment.Kind.Pdf) {
                Column(
                    Modifier.fillMaxSize().verticalScrollCompat().padding(top = 64.dp, bottom = 24.dp, start = 12.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    a.files.forEach { p ->
                        AsyncImage(
                            model = Attachments.file(context, p),
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)),
                        )
                    }
                    if (a.pages > a.files.size) {
                        // Po „z” liczba stron w dopelniaczu: „4 z 22 stron”, nie „z 22 strony”.
                        val seen = a.files.size
                        Lead(
                            if (seen == 1) tr("Model widzi pierwszą z ${a.pages} stron.", "The model sees the first of ${a.pages} pages.")
                            else tr("Model widzi pierwsze $seen z ${a.pages} stron.", "The model sees the first $seen of ${a.pages} pages."),
                            center = true,
                        )
                    }
                }
            } else {
                AsyncImage(
                    model = a.files.firstOrNull()?.let { Attachments.file(context, it) },
                    contentDescription = a.prompt.ifBlank { a.name },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero })
                        }
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y
                        },
                )
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Raise.copy(alpha = 0.8f))
                        .clickable(onClickLabel = tr("Zamknij", "Close"), onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ic_close), tr("Zamknij", "Close"), tint = Paper, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.weight(1f))
                val path = a.files.firstOrNull()
                if (path != null && a.kind != Attachment.Kind.Pdf) {
                    if (Attachments.canSaveToGallery) {
                        MediaAction(R.drawable.ic_download, tr("Zapisz", "Save")) {
                            scope.launch {
                                onNote(
                            if (Attachments.saveToGallery(context, path, a.mime)) tr("Zapisano w galerii (Obrazy/CYPHR).", "Saved to the gallery (Pictures/CYPHR).")
                            else tr("Nie udało się zapisać obrazu.", "Couldn't save the image."),
                        )
                            }
                        }
                    }
                    MediaAction(R.drawable.ic_share, tr("Udostępnij", "Share")) {
                        try { context.startActivity(Attachments.shareIntent(context, path, a.mime)) } catch (_: Exception) {
                            onNote(tr("Nie udało się udostępnić obrazu.", "Couldn't share the image."))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))
