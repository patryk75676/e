package pl.cyphr.app

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ustawienia zapisywane na urzadzeniu. Wartosci domyslne biora sie z gradle.properties. */
enum class Ask { FileEdit, Command, RiskyCommand, AiPage }

object Prefs {
    private const val FILE = "cyphr"
    private lateinit var app: Context

    const val DEFAULT_PROMPT =
        "Jesteś agentem w aplikacji CYPHR. Odpowiadasz po polsku, zwięźle i rzeczowo.\n" +
            "Dostajesz pełną historię rozmowy — czytaj ją i trzymaj się wątku, nie zaczynaj od nowa " +
            "przy każdej wiadomości i nie powtarzaj tego, co już zostało ustalone.\n" +
            "Gdy pytanie odnosi się do czegoś wcześniejszego, odnieś się do tego wprost.\n" +
            "Jeśli czegoś nie wiesz albo brakuje Ci danych, powiedz to zamiast zgadywać."

    private val _homePage = mutableStateOf<String>("https://duckduckgo.com")
    val homePage: String get() = _homePage.value
    private val _animations = mutableStateOf<Boolean>(true)
    val animations: Boolean get() = _animations.value
    private val _desktopMode = mutableStateOf<Boolean>(false)
    val desktopMode: Boolean get() = _desktopMode.value
    private val _agent = mutableStateOf<String?>(null)
    val agent: String? get() = _agent.value

    /** Instrukcja wysylana jako wiadomosc "system" przed kazda rozmowa. */
    private val _systemPrompt = mutableStateOf(DEFAULT_PROMPT)
    val systemPrompt: String get() = _systemPrompt.value

    /**
     * Pytania o zgode. Zapis pliku, polecenia groźne i wysylka strony do modelu
     * pytaja zawsze i nie da sie tego wylaczyc.
     */
    private val _askCommands = mutableStateOf<Boolean>(true)
    val askCommands: Boolean get() = _askCommands.value

    /**
     * Czy model moze prosic o wykonanie polecen w terminalu. Domyslnie tak — kazde
     * takie polecenie i tak czeka na osobna zgode uzytkownika i da sie odmowic.
     */
    private val _agentTerminal = mutableStateOf(true)
    val agentTerminal: Boolean get() = _agentTerminal.value

    fun needsAsk(kind: Ask): Boolean = when (kind) {
        Ask.FileEdit -> true
        Ask.RiskyCommand -> true
        Ask.AiPage -> true
        Ask.Command -> askCommands
    }

    /** Blokada aplikacji odciskiem palca albo kodem ekranu. */
    private val _appLock = mutableStateOf<Boolean>(true)
    val appLock: Boolean get() = _appLock.value

    /** Blokada zrzutow ekranu i podgladu w liscie aplikacji. */
    private val _secureScreen = mutableStateOf<Boolean>(false)
    val secureScreen: Boolean get() = _secureScreen.value

    /** Potwierdzanie zakupu odciskiem palca. */
    private val _confirmBuy = mutableStateOf<Boolean>(true)
    val confirmBuy: Boolean get() = _confirmBuy.value

    /** Po ilu sekundach w tle aplikacja znowu prosi o odcisk palca. */
    private val _lockAfterSeconds = mutableStateOf<Int>(60)
    val lockAfterSeconds: Int get() = _lockAfterSeconds.value

    /** Czy aplikacja juz raz sama poprosila o zgode na sterowanie Termuxem. */
    val termuxAsked: Boolean
        get() = app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("termux_asked", false)

    fun setTermuxAsked() = edit { putBoolean("termux_asked", true) }

    fun init(context: Context) {
        app = context.applicationContext
        val sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        forgetRemovedSettings(sp)
        _homePage.value = sp.getString("home_page", null) ?: "https://duckduckgo.com"
        _animations.value = sp.getBoolean("animations", true)
        _desktopMode.value = sp.getBoolean("desktop_mode", false)
        _agent.value = sp.getString("agent", null)
        _systemPrompt.value = sp.getString("system_prompt", null) ?: DEFAULT_PROMPT
        _askCommands.value = sp.getBoolean("ask_cmd", true)
        _agentTerminal.value = sp.getBoolean("agent_terminal", true)
        _appLock.value = sp.getBoolean("app_lock", true)
        _secureScreen.value = sp.getBoolean("secure_screen_all", false)
        _confirmBuy.value = sp.getBoolean("confirm_buy", true)
        _lockAfterSeconds.value = sp.getInt("lock_after", 60)
    }

    /**
     * Aplikacja jest dla klientow: nie ma juz trybu SSH ani wlasnego adresu serwera.
     * Starsze wersje mogly je zapisac — dane logowania SSH nie powinny zostawac
     * na telefonie, a zapomniany adres testowy odcinalby aplikacje od serwera CYPHR.
     */
    private fun forgetRemovedSettings(sp: android.content.SharedPreferences) {
        val old = listOf("api_url", "ssh_host", "ssh_port", "ssh_user", "favourites")
        if (old.any { sp.contains(it) }) sp.edit().apply { old.forEach { remove(it) } }.apply()
        if (SecureStore.get("ssh_pass") != null) SecureStore.put("ssh_pass", null)
    }

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply(block).apply()
    }

    fun setHomePage(value: String) {
        _homePage.value = value.trim().ifBlank { "https://duckduckgo.com" }
        edit { putString("home_page", homePage) }
    }

    fun setAnimations(value: Boolean) {
        _animations.value = value
        edit { putBoolean("animations", value) }
    }

    fun setDesktopMode(value: Boolean) {
        _desktopMode.value = value
        edit { putBoolean("desktop_mode", value) }
    }

    fun setAskCommands(value: Boolean) { _askCommands.value = value; edit { putBoolean("ask_cmd", value) } }
    fun setAgentTerminal(value: Boolean) { _agentTerminal.value = value; edit { putBoolean("agent_terminal", value) } }
    fun setAppLock(value: Boolean) { _appLock.value = value; edit { putBoolean("app_lock", value) } }
    fun setSecureScreen(value: Boolean) { _secureScreen.value = value; edit { putBoolean("secure_screen_all", value) } }
    fun setConfirmBuy(value: Boolean) { _confirmBuy.value = value; edit { putBoolean("confirm_buy", value) } }
    fun setLockAfter(seconds: Int) { _lockAfterSeconds.value = seconds; edit { putInt("lock_after", seconds) } }

    fun setSystemPrompt(value: String) {
        _systemPrompt.value = value.trim().ifBlank { DEFAULT_PROMPT }
        edit { putString("system_prompt", _systemPrompt.value) }
    }

    fun setAgent(value: String?) {
        _agent.value = value
        edit { if (value == null) remove("agent") else putString("agent", value) }
    }
}

@Composable
fun SettingsScreen(agentName: String?, onTerminal: () -> Unit, onLogout: () -> Unit) {
    var home by remember { mutableStateOf(Prefs.homePage) }
    var homeSaved by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf(Prefs.systemPrompt) }
    var promptSaved by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
    ) {
        SectionTitle("Ustawienia", Modifier.padding(top = 6.dp, bottom = 18.dp))

        Group("Czat") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Model", color = Mist)
                Text(agentName ?: "nie wybrano", color = Paper, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Field(prompt, "Twoje instrukcje dla modelu", { prompt = it; promptSaved = false }, lines = 6)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(if (promptSaved) "Zapisano" else "Zapisz", Modifier.weight(1f)) {
                    Prefs.setSystemPrompt(prompt); prompt = Prefs.systemPrompt; promptSaved = true
                }
                GhostButton("Domyślne", Modifier.weight(1f)) {
                    Prefs.setSystemPrompt(Prefs.DEFAULT_PROMPT); prompt = Prefs.systemPrompt; promptSaved = false
                }
            }
            Spacer(Modifier.height(10.dp))
            Lead(
                "Model bierze je pod uwagę w każdej rozmowie. Długie rozmowy są same streszczane, " +
                    "żeby każda kolejna wiadomość nie kosztowała coraz więcej.",
            )
        }

        Group("Bezpieczeństwo") {
            val bio = Biometrics.state(LocalContext.current)
            val method = (bio as? Biometrics.State.Ready)?.instrumental ?: "blokadą ekranu"
            Lead(
                when (bio) {
                    is Biometrics.State.Ready ->
                        "Drugi składnik logowania na tym telefonie: $method. Po zalogowaniu kontem Google albo hasłem konto otwiera się dopiero po potwierdzeniu i tego nie da się wyłączyć."
                    Biometrics.State.NotEnrolled ->
                        "Ten telefon nie ma jeszcze ustawionej blokady ekranu. Bez niej nie da się zalogować " +
                            "ani przełączyć konta — drugi składnik jest obowiązkowy."
                    Biometrics.State.None ->
                        "To urządzenie nie obsługuje potwierdzania tożsamości, więc nowe logowanie nie przejdzie."
                    Biometrics.State.Unavailable ->
                        "Czytnik jest chwilowo niedostępny."
                },
            )
            if (bio is Biometrics.State.NotEnrolled) {
                Spacer(Modifier.height(12.dp))
                val ctx = LocalContext.current
                GhostButton("Ustaw blokadę ekranu") { ctx.startActivity(Biometrics.enrollIntent()) }
            }
            Spacer(Modifier.height(14.dp))
            Toggle("Blokada po powrocie do aplikacji", Prefs.appLock) { Prefs.setAppLock(it) }
            Spacer(Modifier.height(14.dp))
            LockAfterPicker()
            Spacer(Modifier.height(14.dp))
            Toggle("Potwierdzaj zakupy", Prefs.confirmBuy) { Prefs.setConfirmBuy(it) }
            Spacer(Modifier.height(14.dp))
            if (!SecureStore.encrypted) {
                Lead(
                    "Uwaga: szyfrowany schowek nie wstał na tym urządzeniu. Token sesji jest trzymany " +
                        "tylko w pamięci i zniknie po zamknięciu aplikacji — nie trafia na dysk bez " +
                        "szyfrowania. Trzeba będzie logować się za każdym razem.",
                )
                Spacer(Modifier.height(14.dp))
            }
            Toggle("Blokuj zrzuty ekranu w całej aplikacji", Prefs.secureScreen) { Prefs.setSecureScreen(it) }
            Spacer(Modifier.height(4.dp))
            Lead("Logowanie i rejestracja są chronione zawsze — tam zrzut wyniósłby hasło poza telefon. To ustawienie dotyczy pozostałych ekranów.")
        }

        Group("Przeglądarka") {
            Field(home, "Strona startowa", { home = it; homeSaved = false })
            Spacer(Modifier.height(10.dp))
            GhostButton(if (homeSaved) "Zapisano" else "Zapisz stronę") {
                Prefs.setHomePage(home); home = Prefs.homePage; homeSaved = true
            }
            Spacer(Modifier.height(14.dp))
            Toggle("Widok komputera", Prefs.desktopMode) { Prefs.setDesktopMode(it) }
            Spacer(Modifier.height(14.dp))
            GhostButton(if (cleared) "Wyczyszczono" else "Wyczyść ciasteczka i pamięć") {
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                cleared = true
            }
        }

        Group("Wygląd") {
            Toggle("Animacje", Prefs.animations) { Prefs.setAnimations(it) }
            Spacer(Modifier.height(4.dp))
            Lead("Wyłączone animacje zatrzymują wszystkie ruchy: duszka, kartę, przejścia i dymki.")
        }

        Group("Terminal") {
            TermuxSetup()
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Line, thickness = 1.dp)
            Spacer(Modifier.height(16.dp))
            Toggle("Model może wykonywać polecenia", Prefs.agentTerminal) { Prefs.setAgentTerminal(it) }
            Spacer(Modifier.height(6.dp))
            Lead(
                "Model może poprosić o uruchomienie polecenia w terminalu i przeczytać wynik. " +
                    "Każde polecenie potwierdzasz osobno i możesz odmówić. " +
                    "Idzie do Termuxa, jeśli jest połączony, inaczej do powłoki Androida.",
            )
            Spacer(Modifier.height(16.dp))
            Toggle("Pytaj przed każdym poleceniem", Prefs.askCommands) { Prefs.setAskCommands(it) }
            Spacer(Modifier.height(6.dp))
            Lead(
                "Zapis pliku, polecenia groźne (rm, mv, chmod, dd, przekierowania) oraz wysłanie " +
                    "treści strony do modelu pytają zawsze. Nie ma trybu, który to wyłącza.",
            )
            Spacer(Modifier.height(16.dp))
            GhostButton("Otwórz terminal", onClick = onTerminal)
        }

        GhostButton("Wyloguj się", onClick = onLogout)
        Spacer(Modifier.height(18.dp))
        Lead("CYPHR ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth(), center = true)
        Spacer(Modifier.height(30.dp))
    }
}

/**
 * Po jakim czasie w tle aplikacja znowu prosi o odcisk. Dotyczy wylacznie powrotu
 * do otwartej sesji — nowe logowanie potwierdza sie odciskiem zawsze.
 */
@Composable
private fun LockAfterPicker() {
    val options = listOf(
        60 to "1 min",
        900 to "15 min",
        3600 to "1 godz.",
        86400 to "24 godz.",
    )
    Text("Pyta ponownie po", color = Mist, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (seconds, label) ->
            val on = Prefs.lockAfterSeconds == seconds
            val bg by androidx.compose.animation.animateColorAsState(if (on) Paper else Ink, motionSpec(220), label = "lockBg")
            val edge by androidx.compose.animation.animateColorAsState(if (on) Paper else Line, motionSpec(220), label = "lockEdge")
            val fg by androidx.compose.animation.animateColorAsState(if (on) Ink else Mist, motionSpec(220), label = "lockFg")
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .border(1.5.dp, edge, RoundedCornerShape(50))
                    .clickable { Prefs.setLockAfter(seconds) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = fg,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Lead(
        "Dotyczy powrotu do otwartej sesji. Nowe logowanie potwierdzasz odciskiem " +
            "zawsze, niezależnie od tego ustawienia.",
    )
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Text(title.uppercase(), color = Mist, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.5.dp, Line, RoundedCornerShape(20.dp))
                .padding(16.dp),
            content = content,
        )
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!value) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = if (enabled) Paper else Mist)
        Switch(
            checked = value,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Paper,
                uncheckedThumbColor = Mist,
                uncheckedTrackColor = Ink,
                uncheckedBorderColor = Line,
            ),
        )
    }
}
