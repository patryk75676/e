package pl.cyphr.app

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
 * Podlaczenie Termuxa krok po kroku. Termux domyslnie odrzuca polecenia z innych
 * aplikacji — trzeba mu raz na to pozwolic wpisem w jego wlasnej konfiguracji.
 * Status sprawdzamy realnym poleceniem, bo sama obecnosc aplikacji niczego nie dowodzi.
 */
@Composable
fun TermuxSetup() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Termux.State?>(null) }
    var testing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(Termux.hasPermission(context)) }

    // Termux oznacza swoje uprawnienie jako niebezpieczne, wiec sama deklaracja
    // w manifescie nie wystarczy — system musi o nie spytac uzytkownika.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
    }

    fun test() {
        testing = true
        granted = Termux.hasPermission(context)
        scope.launch {
            state = Termux.check(context)
            testing = false
        }
    }

    LaunchedEffect(Unit) {
        if (Termux.isInstalled(context) && !Termux.hasPermission(context)) {
            ask.launch(Termux.PERMISSION)
        }
        test()
    }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }

    val badge = when (state) {
        Termux.State.Ready -> "Połączony" to Paper
        Termux.State.NoPermission -> "Brak zgody" to Mist
        Termux.State.NotInstalled -> "Nie zainstalowany" to Mist
        null -> "Sprawdzam…" to Mist
    }

    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Termux", color = Paper, fontWeight = FontWeight.Bold)
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (state == Termux.State.Ready) Paper else Ink)
                .border(1.5.dp, if (state == Termux.State.Ready) Paper else Line, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                badge.first,
                color = if (state == Termux.State.Ready) Ink else badge.second,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    when (state) {
        Termux.State.Ready ->
            Lead("Polecenia z terminala CYPHR lecą do Termuxa. Masz tam swój apt, pythona i git.")

        Termux.State.NotInstalled -> {
            Lead(
                "Termux to osobna aplikacja. Musi pochodzić z F-Droid — wersja z Google Play " +
                    "jest porzucona i nie ma potrzebnego API.",
            )
            Spacer(Modifier.height(12.dp))
            GhostButton("Pobierz Termux z F-Droid") { context.startActivity(Termux.storeIntent()) }
        }

        else -> {
            if (!granted) {
                Lead("Aplikacja nie ma jeszcze zgody na sterowanie Termuxem.")
                Spacer(Modifier.height(10.dp))
                GhostButton("Poproś o uprawnienie") { ask.launch(Termux.PERMISSION) }
                Spacer(Modifier.height(14.dp))
            }
            Lead("Termux musi też raz wpuścić polecenia z zewnątrz. Wklej mu to:")
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.5.dp, Line, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text(
                    Termux.SETUP_COMMAND,
                    color = Paper,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(10.dp))
            GhostButton(if (copied) "Skopiowano — wklej w Termuxie" else "Kopiuj polecenie") {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("termux", Termux.SETUP_COMMAND))
                copied = true
            }
            Spacer(Modifier.height(8.dp))
            Termux.launchIntent(context)?.let { intent ->
                GhostButton("Otwórz Termux") { context.startActivity(intent) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    GhostButton(if (testing) "Sprawdzam…" else "Sprawdź połączenie", busy = testing) { test() }
}
