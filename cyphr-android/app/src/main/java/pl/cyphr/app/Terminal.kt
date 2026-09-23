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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun isRisky(command: String): Boolean = CommandRisk.isRisky(command)

/** Pliki wieksze niz to edytor odrzuca — caly tekst trzyma w pamieci i w polu tekstowym. */
private const val MAX_EDIT_BYTES = 512 * 1024

/** Reczne polecenie w Termuxie (np. instalacja pakietow) moze trwac dlugo. */
private const val TERMUX_MANUAL_TIMEOUT_MS = 10 * 60 * 1000L

private enum class Mode { Local, Termux, Ssh }

/**
 * Terminal ma dwa tryby.
 * Lokalny uruchamia polecenia systemu Androida w piaskownicy aplikacji.
 * SSH laczy sie z prawdziwym serwerem i daje pelna powloke z apt, gitem i reszta.
 */
@Composable
fun TerminalTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val home = remember { File(context.filesDir, "home").apply { mkdirs() } }

    var mode by remember { mutableStateOf(if (Prefs.sshReady) Mode.Ssh else Mode.Local) }
    var cwd by remember { mutableStateOf(home) }
    var lines by remember { mutableStateOf(listOf("CYPHR terminal. Wpisz help.")) }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<File, String>?>(null) }
    var saveAsk by remember { mutableStateOf(false) }
    var sshState by remember { mutableStateOf("rozłączony") }
    val listState = rememberLazyListState()

    // Biezace polecenie, zeby dalo sie je przerwac. Proces lokalny zabijamy,
    // a na Termux tylko przestajemy czekac — tam polecenie zyje we wlasnym procesie.
    val process = remember { java.util.concurrent.atomic.AtomicReference<Process?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun push(text: String) {
        if (text.isBlank()) return
        lines = (lines + text.trimEnd().split("\n")).takeLast(600)
    }

    val ssh = remember {
        SshSession(
            scope = scope,
            onOutput = { push(it) },
            onClosed = { reason ->
                sshState = "rozłączony"
                push(reason?.let { "Połączenie zamknięte: $it" } ?: "Połączenie zamknięte.")
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            ssh.disconnect()
            // Wyjscie z terminala nie zostawia w tle polecenia, ktore sie nie konczy.
            process.getAndSet(null)?.destroyForcibly()
            job?.cancel()
        }
    }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }

    fun connectSsh() {
        if (!Prefs.sshReady) { push("Uzupełnij dane SSH w ustawieniach."); return }
        scope.launch {
            sshState = "łączenie"
            push("Łączę z ${Prefs.sshUser}@${Prefs.sshHost}:${Prefs.sshPort}")
            val result = ssh.connect(Prefs.sshHost, Prefs.sshPort, Prefs.sshUser, Prefs.sshPassword().orEmpty())
            if (result.isSuccess) {
                sshState = "połączony"
            } else {
                sshState = "rozłączony"
                push("Błąd: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    LaunchedEffect(mode) { if (mode == Mode.Ssh && sshState == "rozłączony") connectSsh() }

    fun executeLocal(command: String) {
        job = scope.launch {
            running = true
            try {
                withContext(Dispatchers.IO) {
                    val p = ProcessBuilder("/system/bin/sh", "-c", command)
                        .directory(cwd)
                        .redirectErrorStream(true)
                        .start()
                    process.set(p)
                    // Terminal nie ma wejscia dla polecenia — zamkniete od razu sprawia,
                    // ze `cat` bez argumentow konczy sie, zamiast czekac w nieskonczonosc.
                    try { p.outputStream.close() } catch (_: Exception) {}
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
                    if (code != 0) withContext(Dispatchers.Main) { push("[wyjście $code]") }
                }
            } catch (e: Exception) {
                push("Błąd: ${e.message}")
            } finally {
                process.set(null)
                running = false
            }
        }
    }

    fun executeTermux(command: String) {
        job = scope.launch {
            running = true
            try {
                val r = Termux.run(context, command, timeoutMs = TERMUX_MANUAL_TIMEOUT_MS)
                r.stdout.trimEnd().takeIf { it.isNotEmpty() }?.lines()?.forEach { push(it) }
                r.stderr.trimEnd().takeIf { it.isNotEmpty() }?.lines()?.forEach { push(it) }
                if (r.exitCode != 0) push("[wyjście ${r.exitCode}]")
            } finally {
                running = false
            }
        }
    }

    /** Przerywa biezace polecenie. */
    fun stop() {
        val p = process.getAndSet(null)
        if (p != null) {
            p.destroy()
            push("^C przerwano")
        } else if (mode == Mode.Termux) {
            job?.cancel()
            push("^C przestałem czekać — polecenie może dalej działać w Termuksie.")
        }
    }

    fun execute(command: String) {
        if (running && mode != Mode.Ssh) {
            push("Poprzednie polecenie jeszcze działa. Dotknij Stop, żeby je przerwać.")
            return
        }
        when (mode) {
            Mode.Ssh -> {
                if (sshState != "połączony") { push("Brak połączenia. Dotknij Połącz."); return }
                ssh.send(command)
            }
            Mode.Termux -> {
                if (!Termux.isInstalled(context)) {
                    push("Termux nie jest zainstalowany. Wpisz 'termux' po instrukcję.")
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
                        Mode.Ssh ->
                            "Tryb SSH: pełna powłoka serwera. Działa apt, git, nano i cała reszta.\n" +
                                "clear czyści ekran, przełącznik u góry zmienia tryb."
                        Mode.Termux ->
                            "Tryb Termux: polecenia idą do zainstalowanego Termuxa.\n" +
                                "Masz jego apt, pkg, python, git — wszystko co tam zainstalujesz.\n" +
                                "Wpisz 'termux' jeśli coś nie działa, wypiszę jak to ustawić."
                        Mode.Local ->
                            "Tryb lokalny: polecenia systemu Androida (ls, cat, ps, ping, df, getprop).\n" +
                                "cd <katalog> zmienia katalog, edit <plik> otwiera edytor, clear czyści ekran.\n" +
                                "Katalog domowy: ${home.absolutePath}\n" +
                                "To powłoka samego Androida — nie ma apt ani pythona.\n" +
                                "Pełne narzędzia daje tryb Termux albo SSH."
                    },
                )
                return
            }
            command == "termux" -> {
                push(
                    if (Termux.isInstalled(context))
                        "Termux jest zainstalowany.\n\n" +
                            "Jeśli polecenia nie przechodzą, brakuje zgody na sterowanie z zewnątrz:\n" +
                            "1. Otwórz Termux\n" +
                            "2. mkdir -p ~/.termux\n" +
                            "3. echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
                            "4. termux-reload-settings\n" +
                            "5. Wróć tutaj i spróbuj ponownie\n\n" +
                            "Bez tego Termux odrzuca polecenia z innych aplikacji — to jego zabezpieczenie."
                    else
                        "Termux nie jest zainstalowany.\n\n" +
                            "Pobierz go z F-Droid: ${Termux.PLAY_URL}\n" +
                            "Wersja z Google Play jest porzucona i nie działa — musi być z F-Droid albo GitHuba.\n\n" +
                            "Po instalacji otwórz Termux i wykonaj:\n" +
                            "  pkg update && pkg upgrade\n" +
                            "  mkdir -p ~/.termux\n" +
                            "  echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
                            "  termux-reload-settings\n\n" +
                            "Potem wróć tutaj i przełącz tryb na Termux.",
                )
                return
            }
            mode == Mode.Local && command.startsWith("edit ") -> {
                val name = command.removePrefix("edit ").trim()
                val file = if (name.startsWith("/")) File(name) else File(cwd, name)
                // Odczyt poza piaskownica albo wielkiego pliku konczyl sie wyjatkiem
                // na glownym watku, czyli zamknieciem calej aplikacji.
                val text = try {
                    when {
                        file.isDirectory -> { push("edit: $name to katalog"); return }
                        file.isFile && file.length() > MAX_EDIT_BYTES -> {
                            push("edit: plik ma ${file.length() / 1024} KB — edytor przyjmuje do ${MAX_EDIT_BYTES / 1024} KB.")
                            return
                        }
                        file.isFile -> file.readText()
                        else -> ""
                    }
                } catch (e: Exception) {
                    push("edit: nie mogę otworzyć $name (${e.message})")
                    return
                }
                editing = file to text
                return
            }
            mode == Mode.Local && command.startsWith("cd ") -> {
                val target = command.removePrefix("cd ").trim()
                val next = if (target.startsWith("/")) File(target) else File(cwd, target)
                if (next.isDirectory) cwd = next.canonicalFile else push("cd: nie ma katalogu: $target")
                return
            }
        }
        val risky = isRisky(command)
        if (Prefs.needsAsk(if (risky) Ask.RiskyCommand else Ask.Command)) pending = command else execute(command)
    }

    pending?.let { command ->
        val risky = isRisky(command)
        PermissionDialog(
            title = if (risky) "Groźne polecenie" else "Uruchomić polecenie?",
            what = "$ $command",
            detail = if (risky) "Może skasować albo nadpisać dane. Tego pytania nie da się wyłączyć."
            else if (mode == Mode.Ssh) "Serwer: ${Prefs.sshUser}@${Prefs.sshHost}"
            else "Katalog: ${cwd.absolutePath}",
            allowAlways = !risky,
            onAllowOnce = { execute(command); pending = null },
            onAllowAlways = { Prefs.setAskCommands(false); execute(command); pending = null },
            onDeny = { push("Odrzucono."); pending = null },
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
                title = "Zapisać plik?",
                what = file.absolutePath,
                detail = "${text.length} znaków. Poprzednia zawartość zostanie nadpisana.",
                allowAlways = false,
                onAllowOnce = {
                    // Zapis poza piaskownica (np. /system) rzucal wyjatek i zamykal aplikacje.
                    try {
                        file.writeText(text)
                        push("Zapisano ${file.name} (${text.length} znaków).")
                        editing = null
                    } catch (e: Exception) {
                        push("Nie zapisano ${file.name}: ${e.message}")
                    }
                    saveAsk = false
                },
                onAllowAlways = {},
                onDeny = { push("Zapis odrzucony."); saveAsk = false },
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
            ModeChip("Lokalny", mode == Mode.Local) { mode = Mode.Local }
            ModeChip("Termux", mode == Mode.Termux) {
                mode = Mode.Termux
                if (!Termux.isInstalled(context)) push("Termux nie jest zainstalowany. Wpisz 'termux' po instrukcję.")
            }
            ModeChip("SSH", mode == Mode.Ssh) { mode = Mode.Ssh }
            Spacer(Modifier.weight(1f))
            if (mode == Mode.Ssh) {
                Text(sshState, color = if (sshState == "połączony") Paper else Mist, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (sshState == "połączony") "Rozłącz" else "Połącz",
                    color = Paper,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        if (sshState == "połączony") {
                            ssh.disconnect(); sshState = "rozłączony"; push("Rozłączono.")
                        } else connectSsh()
                    },
                )
            }
        }

        Text(
            when (mode) {
                Mode.Ssh -> "${Prefs.sshUser}@${Prefs.sshHost}"
                Mode.Termux -> if (Termux.isInstalled(context)) "termux:~" else "termux — nie zainstalowany"
                Mode.Local -> cwd.absolutePath
            },
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
                placeholder = { Text("polecenie", color = Mist, fontFamily = FontFamily.Monospace) },
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
            val canStop = running && mode != Mode.Ssh
            IconButton(onClick = { if (canStop) stop() else run(input) }) {
                Icon(
                    painterResource(if (canStop) R.drawable.ic_close else R.drawable.ic_send),
                    contentDescription = if (canStop) "Przerwij" else "Uruchom",
                    tint = Paper,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
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
            PrimaryButton("Zapisz", onClick = onSave)
            Spacer(Modifier.height(10.dp))
            GhostButton("Zamknij", onClick = onClose)
        }
    }
}
