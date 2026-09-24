package pl.cyphr.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Podlaczenie Termuxa krok po kroku. Kazdy stan ma wlasna, konkretna instrukcje —
 * wczesniej wszystko, co nie dzialalo, wygladalo jak „brak zgody”, nawet gdy Termux
 * byl za stary, milczal albo sam zglaszal blad.
 */
@Composable
fun TermuxSetup() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Termux.Status?>(null) }
    var testing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    // Zgoda odrzucona na stale — system nie pokaze juz okna, zostaja ustawienia aplikacji.
    var deniedForGood by remember { mutableStateOf(false) }

    fun test() {
        testing = true
        scope.launch {
            // Przycisk ma sprawdzic naprawde, a nie oddac wynik zapamietany przez czat.
            status = Termux.check(context, maxAgeMs = 0)
            testing = false
        }
    }

    // Termux oznacza swoje uprawnienie jako niebezpieczne, wiec sama deklaracja
    // w manifescie nie wystarczy — system musi o nie spytac uzytkownika.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        deniedForGood = !ok && (context as? Activity)?.shouldShowRequestPermissionRationale(Termux.PERMISSION) == false
        test()
    }

    // CYPHR czeka na zgode „Instalowanie nieznanych aplikacji” — po powrocie instalator rusza sam.
    var awaitingInstall by remember { mutableStateOf(false) }

    fun openInstaller(file: java.io.File) {
        if (TermuxInstall.canInstall(context)) {
            try {
                context.startActivity(TermuxInstall.installIntent(context, file))
            } catch (e: android.content.ActivityNotFoundException) {
                context.startActivity(TermuxInstall.pageIntent())
            }
        } else {
            awaitingInstall = true
            context.startActivity(TermuxInstall.allowInstallIntent(context))
        }
    }

    // Samo wejscie w Ustawienia o nic nie pyta — o zgode prosi pierwsze polecenie dla Termuksa.
    // Stan sprawdzamy przy kazdym powrocie na ekran: po odinstalowaniu, instalacji czy zgodzie
    // w oknie systemu sytuacja jest juz inna.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (!testing) test()
                val ready = TermuxInstall.step.value as? TermuxInstall.Step.Ready
                if (awaitingInstall && ready != null && TermuxInstall.canInstall(context)) {
                    awaitingInstall = false
                    openInstaller(ready.file)
                }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }

    val state = status?.state
    // Wlasciwy Termux juz stoi — pobrany plik (ponad 100 MB) jest zbedny.
    LaunchedEffect(state) {
        if (state != null && state !in setOf(Termux.State.NotInstalled, Termux.State.NoCommandApi, Termux.State.TooOld)) {
            TermuxInstall.cleanup(context, installed = true)
        }
    }
    val badge = when (state) {
        null -> tr("Sprawdzam…", "Checking…")
        Termux.State.Ready -> tr("Połączony", "Connected")
        Termux.State.NotInstalled -> tr("Nie zainstalowany", "Not installed")
        Termux.State.NoCommandApi ->
            if (Termux.isPlayBuild(status?.version)) tr("Wersja z Play", "Play version") else tr("Nieobsługiwany", "Unsupported")
        Termux.State.TooOld -> tr("Za stara wersja", "Version too old")
        Termux.State.NoPermission -> tr("Brak zgody", "No permission")
        Termux.State.ExternalAppsBlocked -> tr("Blokuje polecenia", "Blocks commands")
        Termux.State.NoReply -> tr("Nie odpowiada", "Not responding")
        Termux.State.Failed -> tr("Błąd", "Error")
    }
    val good = state == Termux.State.Ready

    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Termux", color = Paper, fontWeight = FontWeight.Bold)
            status?.version?.let { Text(tr("wersja $it", "version $it"), color = Mist, fontSize = 12.sp) }
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (good) Paper else Ink)
                .border(1.5.dp, if (good) Paper else Line, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(badge, color = if (good) Ink else Mist, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    fun SetupCommand(intro: String) {
        Lead(intro)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.5.dp, Line, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(Termux.SETUP_COMMAND, color = Paper, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(10.dp))
        GhostButton(if (copied) tr("Skopiowano — wklej w Termuksie", "Copied — paste it in Termux") else tr("Kopiuj polecenie", "Copy command")) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("termux", Termux.SETUP_COMMAND))
            copied = true
        }
        Spacer(Modifier.height(8.dp))
        Lead(tr("Można je wkleić kilka razy — niczego nie zdubluje.", "You can paste it several times — nothing gets duplicated."))
    }

    @Composable
    fun OpenTermux() {
        Termux.launchIntent(context)?.let { intent ->
            Spacer(Modifier.height(8.dp))
            GhostButton(tr("Otwórz Termux", "Open Termux")) { context.startActivity(intent) }
        }
    }

    when (state) {
        null -> Unit

        Termux.State.Ready ->
            Lead(
                tr(
                    "Polecenia z terminala CYPHR i od modelu idą do Termuksa. Masz tam swój pkg, pythona i gita.",
                    "Commands from the CYPHR terminal and from the model go to Termux. You have your pkg, python and git there.",
                ),
            )

        Termux.State.NotInstalled -> {
            Lead(
                tr(
                    "Termux to osobna aplikacja z terminalem Linuksa. CYPHR pobierze go z F-Droid " +
                        "(ponad 100 MB — najlepiej przez Wi-Fi), sprawdzi, że to oryginał, i otworzy " +
                        "instalator Androida.",
                    "Termux is a separate app with a Linux terminal. CYPHR downloads it from F-Droid " +
                        "(over 100 MB — Wi-Fi is best), checks that it's the original and opens " +
                        "the Android installer.",
                ),
            )
            Spacer(Modifier.height(12.dp))
            InstallTermux(blocked = false, onInstall = ::openInstaller)
        }

        Termux.State.NoCommandApi, Termux.State.TooOld -> {
            Lead(
                when {
                    Termux.isPlayBuild(status?.version) -> tr(
                        "To Termux z Google Play — ta wersja nie przyjmuje poleceń od innych aplikacji " +
                            "i nie da się tego włączyć w żadnych ustawieniach.",
                        "This is Termux from Google Play — this version doesn't accept commands from other apps " +
                            "and no setting can turn that on.",
                    )
                    state == Termux.State.TooOld -> tr(
                        "Masz Termuksa ${status?.version.orEmpty()} — to stara wersja, która nie odsyła " +
                            "wyników poleceń.",
                        "You have Termux ${status?.version.orEmpty()} — an old version that doesn't send " +
                            "command results back.",
                    )
                    else -> tr(
                        "Ta wersja Termuksa nie przyjmuje poleceń od innych aplikacji.",
                        "This Termux version doesn't accept commands from other apps.",
                    )
                } + tr(
                    " CYPHR wymieni go na Termuksa z F-Droid w dwóch krokach. Do tego czasu polecenia " +
                        "modelu idą do powłoki Androida.",
                    " CYPHR will replace it with Termux from F-Droid in two steps. Until then the model's " +
                        "commands go to the Android shell.",
                ),
            )
            Spacer(Modifier.height(14.dp))
            Text(tr("1. Odinstaluj obecny Termux", "1. Uninstall the current Termux"), color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Lead(
                tr(
                    "Android nie podmieni aplikacji podpisanej innym kluczem. Pliki w Termuksie przepadną — przenieś wcześniej, co ważne.",
                    "Android won't replace an app signed with a different key. Files in Termux will be lost — move anything important first.",
                ),
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(tr("Odinstaluj Termux", "Uninstall Termux")) {
                // Nowy Termux pobiera sie w tym czasie w tle.
                TermuxInstall.start(context)
                try {
                    context.startActivity(TermuxInstall.uninstallIntent())
                } catch (e: android.content.ActivityNotFoundException) {
                    context.startActivity(Termux.termuxSettingsIntent())
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(tr("2. Zainstaluj właściwy", "2. Install the right one"), color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            InstallTermux(blocked = true, onInstall = ::openInstaller)
        }

        Termux.State.NoPermission -> {
            if (deniedForGood) {
                // Nazwa dokladnie taka, jak w ustawieniach tego telefonu — nadaje ja Termux, nie CYPHR.
                val label = remember { Termux.permissionLabel(context) }
                    ?: tr("Uruchamianie poleceń w środowisku Termux", "Run commands in Termux environment")
                Lead(
                    tr(
                        "Zgodę odrzucono na stałe, więc system nie pokaże już okna. Włącz ją ręcznie: przycisk " +
                            "niżej → Uprawnienia → „$label” (bywa w „Dodatkowe uprawnienia” albo „Niedozwolone”) " +
                            "→ Zezwalaj. Potem wróć i dotknij „Sprawdź połączenie”.",
                        "The permission was denied permanently, so the system won't show the dialog again. Turn it on " +
                            "manually: the button below → Permissions → \"$label\" (it may be under \"Additional permissions\" " +
                            "or \"Not allowed\") → Allow. Then come back and tap \"Check connection\".",
                    ),
                )
                Spacer(Modifier.height(10.dp))
                GhostButton(tr("Otwórz ustawienia CYPHR", "Open CYPHR settings")) { context.startActivity(Termux.ownSettingsIntent(context)) }
            } else {
                Lead(
                    tr(
                        "CYPHR poprosi o zgodę sam, gdy pierwsze polecenie pójdzie do Termuksa — " +
                            "z terminala albo od modelu. Możesz dać ją też od razu.",
                        "CYPHR asks for permission by itself when the first command goes to Termux — " +
                            "from the terminal or from the model. You can also grant it right away.",
                    ),
                )
                Spacer(Modifier.height(10.dp))
                GhostButton(tr("Daj zgodę teraz", "Grant permission now")) { ask.launch(Termux.PERMISSION) }
            }
        }

        Termux.State.ExternalAppsBlocked -> {
            SetupCommand(tr("Termux odrzuca polecenia z innych aplikacji. Wklej mu raz to polecenie:", "Termux rejects commands from other apps. Paste this command into it once:"))
            OpenTermux()
        }

        Termux.State.NoReply -> {
            Lead(
                tr(
                    "Termux nie odpowiedział. Zwykle pomaga:\n" +
                        "1. Otwórz Termux i poczekaj, aż skończy pierwsze uruchomienie.\n" +
                        "2. Wyłącz dla niego optymalizację baterii — Android usypia go w tle.\n" +
                        "3. Wklej polecenie poniżej, jeśli jeszcze nie zostało wklejone.",
                    "Termux didn't answer. This usually helps:\n" +
                        "1. Open Termux and wait until its first start finishes.\n" +
                        "2. Turn off battery optimization for it — Android puts it to sleep in the background.\n" +
                        "3. Paste the command below if it hasn't been pasted yet.",
                ),
            )
            OpenTermux()
            Spacer(Modifier.height(8.dp))
            GhostButton(tr("Ustawienia Termuksa (bateria)", "Termux settings (battery)")) { context.startActivity(Termux.termuxSettingsIntent()) }
            Spacer(Modifier.height(14.dp))
            SetupCommand(tr("Polecenie dla Termuksa:", "Command for Termux:"))
        }

        Termux.State.Failed -> {
            Lead(tr("Termux zgłosił błąd:", "Termux reported an error:"))
            Spacer(Modifier.height(8.dp))
            Text(
                status?.detail ?: tr("brak szczegółów", "no details"),
                color = Paper,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(14.dp))
            SetupCommand(tr("Jeśli to pierwsze podłączenie, wklej Termuksowi:", "If this is the first connection, paste into Termux:"))
            OpenTermux()
        }
    }

    Spacer(Modifier.height(10.dp))
    GhostButton(if (testing) tr("Sprawdzam…", "Checking…") else tr("Sprawdź połączenie", "Check connection"), busy = testing) { test() }
}

/**
 * Pobranie i instalacja Termuksa z F-Droid. [blocked] — na telefonie stoi jeszcze Termux,
 * ktorego Android nie podmieni (np. z Google Play): plik moze sie juz pobierac, ale instalator
 * otworzy sie dopiero po odinstalowaniu starego.
 */
@Composable
private fun InstallTermux(blocked: Boolean, onInstall: (java.io.File) -> Unit) {
    val context = LocalContext.current
    val step by TermuxInstall.step.collectAsState()
    when (val s = step) {
        TermuxInstall.Step.Idle ->
            if (blocked) Lead(tr("Pobieranie ruszy razem z odinstalowaniem — gotowy plik będzie czekał.", "The download starts together with the uninstall — the file will be ready and waiting."))
            else PrimaryButton(tr("Zainstaluj Termux", "Install Termux")) { TermuxInstall.start(context) }

        is TermuxInstall.Step.Downloading -> {
            val fraction = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else null
            Text(
                tr("Pobieram Termux z F-Droid… ", "Downloading Termux from F-Droid… ") + TermuxInstall.progressLabel(s.done, s.total),
                color = Paper,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    color = Paper,
                    trackColor = Line,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                )
            } else {
                LinearProgressIndicator(
                    color = Paper,
                    trackColor = Line,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                )
            }
            Spacer(Modifier.height(10.dp))
            GhostButton(tr("Przerwij", "Stop")) { TermuxInstall.cancel(context) }
        }

        TermuxInstall.Step.Verifying -> {
            Lead(tr("Sprawdzam, czy to oryginalny Termux podpisany przez F-Droid…", "Checking that it's the original Termux signed by F-Droid…"))
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                color = Paper,
                trackColor = Line,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            )
        }

        is TermuxInstall.Step.Ready ->
            if (blocked) {
                Lead(
                    tr(
                        "Termux pobrany i sprawdzony. Po odinstalowaniu obecnego zainstalujesz go jednym dotknięciem.",
                        "Termux is downloaded and checked. Once the current one is uninstalled, you install it with one tap.",
                    ),
                )
            } else {
                if (!TermuxInstall.canInstall(context)) {
                    Lead(
                        tr(
                            "Android zapyta, czy CYPHR może instalować aplikacje: włącz „Zezwalaj z tego źródła” " +
                                "i wróć — instalator otworzy się sam.",
                            "Android will ask whether CYPHR may install apps: turn on \"Allow from this source\" " +
                                "and come back — the installer opens by itself.",
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                PrimaryButton(tr("Zainstaluj pobrany Termux", "Install downloaded Termux")) { onInstall(s.file) }
                Spacer(Modifier.height(8.dp))
                Lead(
                    tr(
                        "Po instalacji otwórz Termux raz, poczekaj, aż się przygotuje, i wróć tutaj.",
                        "After installing, open Termux once, wait until it's ready and come back here.",
                    ),
                )
            }

        is TermuxInstall.Step.Failed -> {
            Lead(s.message)
            Spacer(Modifier.height(10.dp))
            PrimaryButton(tr("Spróbuj ponownie", "Try again")) { TermuxInstall.start(context) }
            Spacer(Modifier.height(8.dp))
            GhostButton(tr("Otwórz stronę F-Droid", "Open the F-Droid page")) { context.startActivity(TermuxInstall.pageIntent()) }
        }
    }
}

/** Termux na wlasnym ekranie — otwierany z propozycji wymiany przy wejsciu do aplikacji. */
@Composable
fun TermuxSheet(onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Ink)
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
        ) {
            Text(tr("Podłączenie Termuksa", "Connecting Termux"), color = Paper, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(18.dp))
            TermuxSetup()
            Spacer(Modifier.height(10.dp))
            GhostButton(tr("Zamknij", "Close")) { onDismiss() }
        }
    }
}
