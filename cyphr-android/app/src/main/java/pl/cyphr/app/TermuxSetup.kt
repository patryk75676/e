package pl.cyphr.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

    // Samo wejscie w Ustawienia o nic nie pyta — o zgode prosi pierwsze polecenie dla Termuksa.
    LaunchedEffect(Unit) { test() }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }

    val state = status?.state
    val badge = when (state) {
        null -> "Sprawdzam…"
        Termux.State.Ready -> "Połączony"
        Termux.State.NotInstalled -> "Nie zainstalowany"
        Termux.State.TooOld -> "Za stara wersja"
        Termux.State.NoPermission -> "Brak zgody"
        Termux.State.ExternalAppsBlocked -> "Blokuje polecenia"
        Termux.State.NoReply -> "Nie odpowiada"
        Termux.State.Failed -> "Błąd"
    }
    val good = state == Termux.State.Ready

    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Termux", color = Paper, fontWeight = FontWeight.Bold)
            status?.version?.let { Text("wersja $it", color = Mist, fontSize = 12.sp) }
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
        GhostButton(if (copied) "Skopiowano — wklej w Termuksie" else "Kopiuj polecenie") {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("termux", Termux.SETUP_COMMAND))
            copied = true
        }
        Spacer(Modifier.height(8.dp))
        Lead("Można je wkleić kilka razy — niczego nie zdubluje.")
    }

    @Composable
    fun OpenTermux() {
        Termux.launchIntent(context)?.let { intent ->
            Spacer(Modifier.height(8.dp))
            GhostButton("Otwórz Termux") { context.startActivity(intent) }
        }
    }

    when (state) {
        null -> Unit

        Termux.State.Ready ->
            Lead("Polecenia z terminala CYPHR i od modelu idą do Termuksa. Masz tam swój pkg, pythona i gita.")

        Termux.State.NotInstalled -> {
            Lead(
                "Termux to osobna aplikacja. Musi pochodzić z F-Droid albo GitHuba — stara wersja " +
                    "z Google Play nie odsyła wyników poleceń.",
            )
            Spacer(Modifier.height(12.dp))
            GhostButton("Pobierz Termux z F-Droid") { context.startActivity(Termux.storeIntent()) }
        }

        Termux.State.TooOld -> {
            Lead(
                "Masz Termuksa ${status?.version.orEmpty()} — to stara wersja (zwykle z Google Play), " +
                    "która nie odsyła wyników poleceń. Zainstaluj aktualną z F-Droid. Najpierw odinstaluj " +
                    "obecną: Android nie podmieni aplikacji podpisanej innym kluczem, a dane Termuksa przepadną.",
            )
            Spacer(Modifier.height(12.dp))
            GhostButton("Pobierz Termux z F-Droid") { context.startActivity(Termux.storeIntent()) }
        }

        Termux.State.NoPermission -> {
            if (deniedForGood) {
                Lead(
                    "Zgodę odrzucono na stałe, więc system nie pokaże już okna. Włącz ją ręcznie: " +
                        "Uprawnienia → Dodatkowe uprawnienia → Uruchamianie poleceń w środowisku Termux.",
                )
                Spacer(Modifier.height(10.dp))
                GhostButton("Otwórz ustawienia CYPHR") { context.startActivity(Termux.ownSettingsIntent(context)) }
            } else {
                Lead(
                    "CYPHR poprosi o zgodę sam, gdy pierwsze polecenie pójdzie do Termuksa — " +
                        "z terminala albo od modelu. Możesz dać ją też od razu.",
                )
                Spacer(Modifier.height(10.dp))
                GhostButton("Daj zgodę teraz") { ask.launch(Termux.PERMISSION) }
            }
        }

        Termux.State.ExternalAppsBlocked -> {
            SetupCommand("Termux odrzuca polecenia z innych aplikacji. Wklej mu raz to polecenie:")
            OpenTermux()
        }

        Termux.State.NoReply -> {
            Lead(
                "Termux nie odpowiedział. Zwykle pomaga:\n" +
                    "1. Otwórz Termux i poczekaj, aż skończy pierwsze uruchomienie.\n" +
                    "2. Wyłącz dla niego optymalizację baterii — Android usypia go w tle.\n" +
                    "3. Wklej polecenie poniżej, jeśli jeszcze tego nie zrobiłeś.",
            )
            OpenTermux()
            Spacer(Modifier.height(8.dp))
            GhostButton("Ustawienia Termuksa (bateria)") { context.startActivity(Termux.termuxSettingsIntent()) }
            Spacer(Modifier.height(14.dp))
            SetupCommand("Polecenie dla Termuksa:")
        }

        Termux.State.Failed -> {
            Lead("Termux zgłosił błąd:")
            Spacer(Modifier.height(8.dp))
            Text(
                status?.detail ?: "brak szczegółów",
                color = Paper,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(14.dp))
            SetupCommand("Jeśli to pierwsze podłączenie, wklej Termuksowi:")
            OpenTermux()
        }
    }

    Spacer(Modifier.height(10.dp))
    GhostButton(if (testing) "Sprawdzam…" else "Sprawdź połączenie", busy = testing) { test() }
}
