package pl.cyphr.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun isRisky(command: String): Boolean = CommandRisk.isRisky(command)

/** Pliki wieksze niz to edytor odrzuca — caly tekst trzyma w pamieci i w polu tekstowym. */
private const val MAX_EDIT_BYTES = 512 * 1024

/** Reczne polecenie w Termuxie (np. instalacja pakietow) moze trwac dlugo. */
private const val TERMUX_MANUAL_TIMEOUT_MS = 10 * 60 * 1000L

private enum class Mode { Local, Termux }

/**
 * Terminal ma dwa tryby.
 * Lokalny uruchamia polecenia systemu Androida w piaskownicy aplikacji.
 * Termux wysyla je do zainstalowanego Termuksa — z jego pakietami, pythonem i gitem.
 */
@Composable
fun TerminalTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val home = remember { File(context.filesDir, "home").apply { mkdirs() } }

    var mode by remember { mutableStateOf(Mode.Local) }
    var cwd by remember { mutableStateOf(home) }
    // Termux uruchamia kazde polecenie osobno, wiec katalog pamietamy tutaj.
    var termuxCwd by remember { mutableStateOf(Termux.HOME) }
    // Ile sekund dziala biezace polecenie — przy dlugich (pkg install) widac, ze cos sie dzieje.
    var elapsed by remember { mutableStateOf(0) }
    var lines by remember { mutableStateOf(listOf(tr("CYPHR terminal. Wpisz help.", "CYPHR terminal. Type help."))) }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<File, String>?>(null) }
    var saveAsk by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Biezace polecenie, zeby dalo sie je przerwac. Proces lokalny zabijamy,
    // a na Termux tylko przestajemy czekac — tam polecenie zyje we wlasnym procesie.
    val process = remember { java.util.concurrent.atomic.AtomicReference<Shell.Running?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun push(text: String) {
        if (text.isBlank()) return
        lines = (lines + text.trimEnd().split("\n")).takeLast(600)
    }

    DisposableEffect(Unit) {
        onDispose {
            // Wyjscie z terminala nie zostawia w tle polecenia, ktore sie nie konczy.
            process.getAndSet(null)?.kill()
            job?.cancel()
        }
    }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }

    LaunchedEffect(running) {
        elapsed = 0
        if (running) {
            val start = System.currentTimeMillis()
            while (true) {
                delay(1000)
                elapsed = ((System.currentTimeMillis() - start) / 1000).toInt()
            }
        }
    }

    fun executeLocal(command: String) {
        job = scope.launch {
            running = true
            try {
                withContext(Dispatchers.IO) {
                    // Shell.start zamyka wejscie (cat bez argumentow sie konczy) i pozwala
                    // przerwac cale drzewo procesow, a nie tylko sama powloke.
                    val shell = Shell.start(command, cwd, pidDir = context.cacheDir)
                    process.set(shell)
                    val p = shell.process
                    // Linie trafiaja na ekran w trakcie dzialania komendy, ale paczkami —
                    // przy wyniku na tysiace linii oddzielne przerysowanie na kazda
                    // zatkaloby interfejs.
                    val batch = mutableListOf<String>()
                    suspend fun flush() {
                        if (batch.isEmpty()) return
                        val chunk = batch.joinToString("\n")
                        batch.clear()
                        withContext(Dispatchers.Main) { push(chunk) }
                    }
                    p.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            batch += line
                            if (batch.size >= 40) flush()
                        }
                    }
                    flush()
                    val code = p.waitFor()
                    shell.cleanup()
                    if (code != 0 && process.get() != null) withContext(Dispatchers.Main) { push(tr("[wyjście $code]", "[exit $code]")) }
                }
            } catch (e: Exception) {
                push(tr("Błąd: ${e.message}", "Error: ${e.message}"))
            } finally {
                process.set(null)
                running = false
            }
        }
    }

    /** Brak zgody na Termuxa po pytaniu systemu — mowimy, co dalej, zamiast milczec. */
    fun noTermuxPermission() = push(
        if (!Termux.acceptsCommands(context)) {
            Termux.noCommandApi() + tr(" Wpisz 'termux' po instrukcję.", " Type 'termux' for instructions.")
        } else {
            tr(
                "Bez zgody na sterowanie Termuksem polecenie nie pójdzie. Zezwól, gdy Android zapyta, albo w Ustawienia → Terminal.",
                "Without permission to control Termux the command won't go through. Allow it when Android asks, or in Settings → Terminal.",
            )
        },
    )

    fun executeTermux(command: String) {
        job = scope.launch {
            running = true
            try {
                // Zgoda prosi sie sama, przy pierwszym poleceniu — nie trzeba jej szukac w Ustawieniach.
                if (!TermuxPermission.ensure(context)) { noTermuxPermission(); return@launch }
                val r = Termux.run(context, command, workdir = termuxCwd, timeoutMs = TERMUX_MANUAL_TIMEOUT_MS)
                r.stdout.trimEnd().takeIf { it.isNotEmpty() }?.lines()?.forEach { push(it) }
                r.stderr.trimEnd().takeIf { it.isNotEmpty() }?.lines()?.forEach { push(it) }
                // Wlasny komunikat Termuxa mowi, co jest nie tak — wczesniej ginal.
                r.errMsg?.let { push("Termux: $it") }
                if (r.failure != null && r.failure != Termux.Failure.NoReply) push(tr("Wpisz 'termux' — wypiszę, jak to naprawić.", "Type 'termux' — it lists how to fix this."))
                if (r.truncated) push(tr("(Termux obciął początek wyniku — oddaje najwyżej ok. 100 KB)", "(Termux cut off the start of the output — it returns at most about 100 KB)"))
                if (r.failure == null && r.exitCode != 0) push(tr("[wyjście ${r.exitCode}]", "[exit ${r.exitCode}]"))
            } finally {
                running = false
            }
        }
    }

    /** `cd` w Termuksie: sprawdzamy katalog u niego i zapamietujemy pelna sciezke. */
    fun changeTermuxDir(target: String) {
        job = scope.launch {
            running = true
            try {
                if (!TermuxPermission.ensure(context)) { noTermuxPermission(); return@launch }
                val r = Termux.run(context, "cd -- ${Termux.quote(target)} && pwd", workdir = termuxCwd, timeoutMs = 15_000)
                val path = r.stdout.trim().lines().lastOrNull()?.trim()
                if (r.failure == null && r.exitCode == 0 && path != null && path.startsWith("/")) {
                    termuxCwd = path
                } else {
                    push(r.errMsg ?: r.stderr.trim().ifBlank { tr("cd: nie ma katalogu: $target", "cd: no such directory: $target") })
                }
            } finally {
                running = false
            }
        }
    }

    /** Przerywa biezace polecenie. */
    fun stop() {
        val shell = process.getAndSet(null)
        if (shell != null) {
            shell.kill()
            push(tr("^C przerwano", "^C stopped"))
        } else if (mode == Mode.Termux) {
            job?.cancel()
            push(tr("^C koniec czekania — polecenie może dalej działać w Termuksie.", "^C stopped waiting — the command may still be running in Termux."))
        }
    }

    fun execute(command: String) {
        if (running) {
            push(busyNote())
            return
        }
        when (mode) {
            Mode.Termux -> {
                if (!Termux.isInstalled(context)) {
                    push(missingNote())
                    return
                }
                executeTermux(command)
            }
            Mode.Local -> executeLocal(command)
        }
    }

    fun run(command: String) {
        push("$ $command")
        input = ""
        if (command.isBlank()) return
        when {
            command == "clear" -> { lines = emptyList(); return }
            command == "help" -> {
                push(
                    when (mode) {
                        Mode.Termux -> tr(
                            "Tryb Termux: polecenia idą do zainstalowanego Termuksa.\n" +
                                "Masz jego pkg, pythona, gita — wszystko, co tam zainstalujesz.\n" +
                                "cd <katalog> działa i jest pamiętany, ~ to katalog domowy Termuksa.\n" +
                                "Wynik pojawia się po zakończeniu polecenia; Stop przestaje czekać.\n" +
                                "Wpisz 'termux', jeśli coś nie działa — wypiszę, jak to ustawić.",
                            "Termux mode: commands go to the installed Termux.\n" +
                                "You have its pkg, python, git — everything you install there.\n" +
                                "cd <directory> works and is remembered, ~ is Termux's home directory.\n" +
                                "The output appears when the command ends; Stop stops waiting.\n" +
                                "Type 'termux' if something doesn't work — it lists how to set it up.",
                        )
                        Mode.Local -> tr(
                            "Tryb lokalny: polecenia systemu Androida (ls, cat, ps, ping, df, getprop).\n" +
                                "cd <katalog> zmienia katalog, edit <plik> otwiera edytor, clear czyści ekran.\n" +
                                "Katalog domowy: ${home.absolutePath}\n" +
                                "To powłoka samego Androida — nie ma apt ani pythona.\n" +
                                "Pełne narzędzia daje tryb Termux.",
                            "Local mode: Android system commands (ls, cat, ps, ping, df, getprop).\n" +
                                "cd <directory> changes the directory, edit <file> opens the editor, clear clears the screen.\n" +
                                "Home directory: ${home.absolutePath}\n" +
                                "This is Android's own shell — there's no apt or python.\n" +
                                "Termux mode gives you the full tools.",
                        )
                    },
                )
                return
            }
            command == "termux" -> {
                push(
                    if (isEn) termuxHelpEn(Termux.isInstalled(context), Termux.acceptsCommands(context))
                    else if (Termux.isInstalled(context) && !Termux.acceptsCommands(context))
                        "Ten Termux jest z Google Play. To osobna wersja, która nie przyjmuje poleceń od innych " +
                            "aplikacji — nie ma w niej zgody na sterowanie z zewnątrz, więc nie da się jej włączyć.\n\n" +
                            "CYPHR wymieni go sam: Ustawienia → Terminal → „Odinstaluj Termux”, a potem\n" +
                            "„Zainstaluj pobrany Termux” — właściwy pobiera się z F-Droid w tle i jest sprawdzany.\n" +
                            "Pliki w obecnym Termuksie przepadną — przenieś wcześniej, co ważne.\n\n" +
                            "Potem otwórz Termux, poczekaj na koniec instalacji i wklej:\n" +
                            "   ${Termux.SETUP_COMMAND}\n" +
                            "O zgodę CYPHR zapyta sam przy pierwszym poleceniu."
                    else if (Termux.isInstalled(context))
                        "Termux jest zainstalowany. Dokładny stan i naprawę krok po kroku masz w " +
                            "Ustawienia → Terminal.\n\n" +
                            "Najczęstsze przyczyny, gdy polecenia nie przechodzą:\n" +
                            "1. Termux blokuje polecenia z innych aplikacji. Wklej mu raz:\n" +
                            "   ${Termux.SETUP_COMMAND}\n" +
                            "2. Odmówiono zgody na sterowanie Termuksem — CYPHR pyta przy pierwszym poleceniu;\n" +
                            "   po odmowie na stałe włączysz ją w Ustawienia → Terminal.\n" +
                            "3. Termux nigdy nie był otwierany — otwórz go raz i poczekaj na koniec instalacji.\n" +
                            "4. Android usypia Termuksa — wyłącz mu optymalizację baterii.\n" +
                            "5. Termux z Google Play nie przyjmuje poleceń od innych aplikacji — potrzebny z F-Droid."
                    else
                        "Termux nie jest zainstalowany.\n\n" +
                            "Zainstalujesz go jednym dotknięciem: Ustawienia → Terminal → „Zainstaluj Termux”.\n" +
                            "CYPHR pobierze go z F-Droid, sprawdzi, że to oryginał, i otworzy instalator Androida.\n" +
                            "Wersja z Google Play nie przyjmuje poleceń od innych aplikacji — ta z F-Droid tak.\n\n" +
                            "Po instalacji otwórz Termux, poczekaj na koniec instalacji i wklej:\n" +
                            "  ${Termux.SETUP_COMMAND}\n\n" +
                            "Potem wróć tutaj i przełącz tryb na Termux.",
                )
                return
            }
            mode == Mode.Termux && (command == "cd" || command.startsWith("cd ")) &&
                Termux.cdTarget(command.removePrefix("cd")) != null -> {
                if (running) { push(busyNote()); return }
                if (!Termux.isInstalled(context)) { push(missingNote()); return }
                changeTermuxDir(Termux.cdTarget(command.removePrefix("cd"))!!)
                return
            }
            mode == Mode.Local && command.startsWith("edit ") -> {
                val name = command.removePrefix("edit ").trim()
                val file = if (name.startsWith("/")) File(name) else File(cwd, name)
                // Odczyt poza piaskownica albo wielkiego pliku konczyl sie wyjatkiem
                // na glownym watku, czyli zamknieciem calej aplikacji.
                val text = try {
                    when {
                        file.isDirectory -> { push(tr("edit: $name to katalog", "edit: $name is a directory")); return }
                        file.isFile && file.length() > MAX_EDIT_BYTES -> {
                            push(
                                tr(
                                    "edit: plik ma ${file.length() / 1024} KB — edytor przyjmuje do ${MAX_EDIT_BYTES / 1024} KB.",
                                    "edit: the file is ${file.length() / 1024} KB — the editor takes up to ${MAX_EDIT_BYTES / 1024} KB.",
                                ),
                            )
                            return
                        }
                        file.isFile -> file.readText()
                        else -> ""
                    }
                } catch (e: Exception) {
                    push(tr("edit: nie mogę otworzyć $name (${e.message})", "edit: can't open $name (${e.message})"))
                    return
                }
                editing = file to text
                return
            }
            mode == Mode.Local && (command == "cd" || command.startsWith("cd ")) -> {
                val target = command.removePrefix("cd").trim()
                val next = when {
                    target.isEmpty() || target == "~" -> home
                    target.startsWith("~/") -> File(home, target.removePrefix("~/"))
                    target.startsWith("/") -> File(target)
                    else -> File(cwd, target)
                }
                if (next.isDirectory) cwd = next.canonicalFile else push(tr("cd: nie ma katalogu: $target", "cd: no such directory: $target"))
                return
            }
        }
        val risky = isRisky(command)
        if (Prefs.needsAsk(if (risky) Ask.RiskyCommand else Ask.Command)) pending = command else execute(command)
    }

    pending?.let { command ->
        val risky = isRisky(command)
        PermissionDialog(
            title = if (risky) tr("Groźne polecenie", "Dangerous command") else tr("Uruchomić polecenie?", "Run the command?"),
            what = "$ $command",
            detail = if (risky) tr("Może skasować albo nadpisać dane. Tego pytania nie da się wyłączyć.", "It may delete or overwrite data. This question can't be turned off.")
            else if (mode == Mode.Termux) tr("Termux, katalog ${Termux.shortPath(termuxCwd)}", "Termux, directory ${Termux.shortPath(termuxCwd)}")
            else tr("Katalog: ${cwd.absolutePath}", "Directory: ${cwd.absolutePath}"),
            allowAlways = !risky,
            onAllowOnce = { execute(command); pending = null },
            onAllowAlways = { Prefs.setAskCommands(false); execute(command); pending = null },
            onDeny = { push(tr("Odrzucono.", "Denied.")); pending = null },
        )
    }

    editing?.let { (file, text) ->
        FileEditor(
            file = file,
            text = text,
            onText = { editing = file to it },
            onClose = { editing = null },
            onSave = { saveAsk = true },
        )
        if (saveAsk) {
            PermissionDialog(
                title = tr("Zapisać plik?", "Save the file?"),
                what = file.absolutePath,
                detail = tr("${text.length} znaków. Poprzednia zawartość zostanie nadpisana.", "${text.length} characters. The previous content will be overwritten."),
                allowAlways = false,
                onAllowOnce = {
                    // Zapis poza piaskownica (np. /system) rzucal wyjatek i zamykal aplikacje.
                    try {
                        file.writeText(text)
                        push(tr("Zapisano ${file.name} (${text.length} znaków).", "Saved ${file.name} (${text.length} characters)."))
                        editing = null
                    } catch (e: Exception) {
                        push(tr("Nie zapisano ${file.name}: ${e.message}", "Didn't save ${file.name}: ${e.message}"))
                    }
                    saveAsk = false
                },
                onAllowAlways = {},
                onDeny = { push(tr("Zapis odrzucony.", "Save denied.")); saveAsk = false },
            )
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Przelacznik trybu
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeChip(tr("Lokalny", "Local"), mode == Mode.Local) { mode = Mode.Local }
            ModeChip("Termux", mode == Mode.Termux) {
                mode = Mode.Termux
                if (!Termux.isInstalled(context)) push(missingNote())
            }
        }

        Text(
            when (mode) {
                Mode.Termux -> if (Termux.isInstalled(context)) "termux:${Termux.shortPath(termuxCwd)}" else tr("termux — nie zainstalowany", "termux — not installed")
                Mode.Local -> cwd.absolutePath
            } + if (running && elapsed >= 2) tr("   ·  działa $elapsed s", "   ·  running $elapsed s") else "",
            color = Mist, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            items(lines) { line ->
                Text(
                    line,
                    color = if (line.startsWith("$ ")) Paper else Mist,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text(tr("polecenie", "command"), color = Mist, fontFamily = FontFamily.Monospace) },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { run(input) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Raise, unfocusedContainerColor = Raise,
                    focusedTextColor = Paper, unfocusedTextColor = Paper, cursorColor = Paper,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            // Gdy polecenie dziala, ten sam przycisk je przerywa — inaczej `ping`
            // bez -c blokowal terminal na zawsze.
            val canStop = running
            IconButton(onClick = { if (canStop) stop() else run(input) }) {
                Icon(
                    painterResource(if (canStop) R.drawable.ic_close else R.drawable.ic_send),
                    contentDescription = if (canStop) tr("Przerwij", "Stop") else tr("Uruchom", "Run"),
                    tint = Paper,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun busyNote() = tr(
    "Poprzednie polecenie jeszcze działa. Dotknij Stop, żeby je przerwać.",
    "The previous command is still running. Tap Stop to interrupt it.",
)

private fun missingNote() = tr(
    "Termux nie jest zainstalowany. Wpisz 'termux' po instrukcję.",
    "Termux isn't installed. Type 'termux' for instructions.",
)

/** Pomoc do polecenia `termux` po angielsku — trzy przypadki jak w wersji polskiej. */
private fun termuxHelpEn(installed: Boolean, acceptsCommands: Boolean): String = when {
    installed && !acceptsCommands ->
        "This Termux is from Google Play. It's a separate version that doesn't accept commands from other " +
            "apps — it has no permission for outside control, so it can't be turned on.\n\n" +
            "CYPHR will replace it for you: Settings → Terminal → \"Uninstall Termux\", then\n" +
            "\"Install downloaded Termux\" — the right one downloads from F-Droid in the background and gets checked.\n" +
            "Files in the current Termux will be lost — move anything important first.\n\n" +
            "Then open Termux, wait for the setup to finish and paste:\n" +
            "   ${Termux.SETUP_COMMAND}\n" +
            "CYPHR asks for permission by itself on the first command."
    installed ->
        "Termux is installed. The exact state and a step-by-step fix are in " +
            "Settings → Terminal.\n\n" +
            "Most common reasons commands don't go through:\n" +
            "1. Termux blocks commands from other apps. Paste this into it once:\n" +
            "   ${Termux.SETUP_COMMAND}\n" +
            "2. Permission to control Termux was denied — CYPHR asks on the first command;\n" +
            "   after a permanent denial you turn it on in Settings → Terminal.\n" +
            "3. Termux has never been opened — open it once and wait for the setup to finish.\n" +
            "4. Android puts Termux to sleep — turn off battery optimization for it.\n" +
            "5. Termux from Google Play doesn't accept commands from other apps — you need the F-Droid one."
    else ->
        "Termux isn't installed.\n\n" +
            "You install it with one tap: Settings → Terminal → \"Install Termux\".\n" +
            "CYPHR downloads it from F-Droid, checks it's the original and opens the Android installer.\n" +
            "The Google Play version doesn't accept commands from other apps — the F-Droid one does.\n\n" +
            "After installing, open Termux, wait for the setup to finish and paste:\n" +
            "  ${Termux.SETUP_COMMAND}\n\n" +
            "Then come back here and switch the mode to Termux."
}

@Composable
private fun ModeChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .then(if (on) Modifier.background(Paper) else Modifier.border(1.5.dp, Line, RoundedCornerShape(50)))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (on) Ink else Mist, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEditor(
    file: File,
    text: String,
    onText: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Raise, contentColor = Paper) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text(file.name, color = Paper, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(file.absolutePath, color = Mist, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(14.dp))
            TextField(
                value = text,
                onValueChange = onText,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Ink, unfocusedContainerColor = Ink,
                    focusedTextColor = Paper, unfocusedTextColor = Paper, cursorColor = Paper,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton(tr("Zapisz", "Save"), onClick = onSave)
            Spacer(Modifier.height(10.dp))
            GhostButton(tr("Zamknij", "Close"), onClick = onClose)
        }
    }
}
