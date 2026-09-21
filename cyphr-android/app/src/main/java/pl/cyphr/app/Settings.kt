package pl.cyphr.app

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
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

    private val _apiUrl = mutableStateOf<String>(BuildConfig.BASE_URL)
    val apiUrl: String get() = _apiUrl.value
    private val _homePage = mutableStateOf<String>("https://duckduckgo.com")
    val homePage: String get() = _homePage.value
    private val _animations = mutableStateOf<Boolean>(true)
    val animations: Boolean get() = _animations.value
    private val _desktopMode = mutableStateOf<Boolean>(false)
    val desktopMode: Boolean get() = _desktopMode.value
    private val _agent = mutableStateOf<String?>(null)
    val agent: String? get() = _agent.value

    /** Modele przypiete na gorze listy agentow. */
    private val _favourites = mutableStateOf<Set<String>>(emptySet())
    val favourites: Set<String> get() = _favourites.value

    fun isFavourite(id: String): Boolean = id in _favourites.value

    fun toggleFavourite(id: String) {
        _favourites.value = _favourites.value.let { if (id in it) it - id else it + id }
        edit { putStringSet("favourites", _favourites.value) }
    }

    /** Instrukcja wysylana jako wiadomosc "system" przed kazda rozmowa. */
    private val _systemPrompt = mutableStateOf(DEFAULT_PROMPT)
    val systemPrompt: String get() = _systemPrompt.value

    /**
     * Pytania o zgode. Zapis pliku, polecenia groźne i wysylka strony do modelu
     * pytaja zawsze i nie da sie tego wylaczyc.
     */
    private val _askCommands = mutableStateOf<Boolean>(true)
    val askCommands: Boolean get() = _askCommands.value

    /** Czy model moze prosic o wykonanie polecen w terminalu. Domyslnie nie. */
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

    // Dane do prawdziwego terminala po SSH
    private val _sshHost = mutableStateOf<String>("")
    val sshHost: String get() = _sshHost.value
    private val _sshPort = mutableStateOf<Int>(22)
    val sshPort: Int get() = _sshPort.value
    private val _sshUser = mutableStateOf<String>("")
    val sshUser: String get() = _sshUser.value

    fun sshPassword(): String? = SecureStore.get("ssh_pass")

    val sshReady: Boolean get() = sshHost.isNotBlank() && sshUser.isNotBlank() && !sshPassword().isNullOrBlank()

    fun init(context: Context) {
        app = context.applicationContext
        val sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _apiUrl.value = sp.getString("api_url", null)?.ifBlank { null } ?: BuildConfig.BASE_URL
        _homePage.value = sp.getString("home_page", null) ?: "https://duckduckgo.com"
        _animations.value = sp.getBoolean("animations", true)
        _desktopMode.value = sp.getBoolean("desktop_mode", false)
        _agent.value = sp.getString("agent", null)
        _systemPrompt.value = sp.getString("system_prompt", null) ?: DEFAULT_PROMPT
        _favourites.value = sp.getStringSet("favourites", null)?.toSet() ?: emptySet()
        _askCommands.value = sp.getBoolean("ask_cmd", true)
        _agentTerminal.value = sp.getBoolean("agent_terminal", true)
        _appLock.value = sp.getBoolean("app_lock", true)
        _secureScreen.value = sp.getBoolean("secure_screen_all", false)
        _confirmBuy.value = sp.getBoolean("confirm_buy", true)
        _lockAfterSeconds.value = sp.getInt("lock_after", 60)
        _sshHost.value = sp.getString("ssh_host", "") ?: ""
        _sshPort.value = sp.getInt("ssh_port", 22)
        _sshUser.value = sp.getString("ssh_user", "") ?: ""
    }

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply(block).apply()
    }

    /**
     * Zwraca null gdy adres przyjeto, albo powod odmowy. Token sesji leci w naglowku
     * kazdego zapytania, wiec adres bez https odrzucamy tutaj, zamiast pozwolic
     * aplikacji probowac i zglaszac potem mylacy brak polaczenia.
     */
    fun setApiUrl(value: String): String? {
        val clean = value.trim().trimEnd('/')
        if (clean.isBlank()) {
            _apiUrl.value = BuildConfig.BASE_URL
            edit { putString("api_url", _apiUrl.value) }
            return null
        }
        if (!clean.startsWith("https://")) return "Adres musi zaczynać się od https://"
        if (clean.removePrefix("https://").isBlank()) return "Brakuje nazwy serwera."
        _apiUrl.value = clean
        edit { putString("api_url", clean) }
        return null
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

    fun setSsh(host: String, port: Int, user: String, password: String?) {
        _sshHost.value = host.trim(); _sshPort.value = port; _sshUser.value = user.trim()
        edit {
            putString("ssh_host", sshHost)
            putInt("ssh_port", sshPort)
            putString("ssh_user", sshUser)
        }
        if (password != null) SecureStore.put("ssh_pass", password)
    }

    fun clearSsh() {
        _sshHost.value = ""; _sshUser.value = ""; _sshPort.value = 22
        edit { remove("ssh_host"); remove("ssh_user"); remove("ssh_port") }
        SecureStore.put("ssh_pass", null)
    }

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
fun SettingsScreen(agentName: String?, onTerminal: () -> Unit, onLogout: () -> Unit, onClosed: () -> Unit) {
    var api by remember { mutableStateOf(Prefs.apiUrl) }
    var home by remember { mutableStateOf(Prefs.homePage) }
    var host by remember { mutableStateOf(Prefs.sshHost) }
    var port by remember { mutableStateOf(Prefs.sshPort.toString()) }
    var sshUser by remember { mutableStateOf(Prefs.sshUser) }
    var sshPass by remember { mutableStateOf("") }
    var sshSaved by remember { mutableStateOf(Prefs.sshReady) }
    var cleared by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf(Prefs.systemPrompt) }
    var apiError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
    ) {
        SectionTitle("Ustawienia", Modifier.padding(top = 6.dp, bottom = 18.dp))

        Group("Połączenie") {
            Field(api, "Adres API", { api = it; apiError = null })
            Spacer(Modifier.height(10.dp))
            GhostButton("Zapisz adres") {
                apiError = Prefs.setApiUrl(api)
                if (apiError == null) { api = Prefs.apiUrl; onClosed() }
            }
            Spacer(Modifier.height(8.dp))
            ErrorText(apiError)
            Lead("Domyślnie: ${BuildConfig.BASE_URL}. Dozwolone jest wyłącznie https.")
        }

        Group("Terminal — Termux") {
            TermuxSetup()
        }

        Group("Terminal SSH") {
            Lead("Tryb SSH daje prawdziwą powłokę serwera, z apt, gitem i resztą narzędzi.")
            Spacer(Modifier.height(12.dp))
            Field(host, "Host", { host = it })
            Spacer(Modifier.height(10.dp))
            Field(port, "Port", { port = it.filter { c -> c.isDigit() }.take(5) }, keyboard = KeyboardType.Number)
            Spacer(Modifier.height(10.dp))
            Field(sshUser, "Użytkownik", { sshUser = it })
            Spacer(Modifier.height(10.dp))
            Field(sshPass, if (sshSaved) "Hasło (zapisane)" else "Hasło", { sshPass = it }, password = true)
            Spacer(Modifier.height(12.dp))
            GhostButton("Zapisz dane SSH") {
                Prefs.setSsh(host, port.toIntOrNull() ?: 22, sshUser, sshPass.ifBlank { null })
                sshPass = ""
                sshSaved = Prefs.sshReady
            }
            Spacer(Modifier.height(10.dp))
            GhostButton("Usuń dane SSH") {
                Prefs.clearSsh(); host = ""; sshUser = ""; port = "22"; sshPass = ""; sshSaved = false
            }
            Spacer(Modifier.height(8.dp))
            Lead("Hasło trzyma szyfrowany schowek oparty o Keystore telefonu. Klucz serwera jest zapamiętywany i sprawdzany przy każdym połączeniu.")
        }

        Group("Uprawnienia") {
            Toggle("Model może wykonywać polecenia", Prefs.agentTerminal) { Prefs.setAgentTerminal(it) }
            Spacer(Modifier.height(6.dp))
            Lead(
                "Model może poprosić o uruchomienie polecenia w terminalu i przeczytać wynik. " +
                    "Każde polecenie potwierdzasz osobno i możesz odmówić. " +
                    "Idzie do Termuxa, jeśli jest połączony, inaczej do powłoki Androida.",
            )
            Spacer(Modifier.height(16.dp))
            Toggle("Pytaj przed każdym poleceniem", Prefs.askCommands) { Prefs.setAskCommands(it) }
            Spacer(Modifier.height(12.dp))
            Lead(
                "Zapis pliku, polecenia groźne (rm, mv, chmod, dd, przekierowania) oraz wysłanie " +
                    "treści strony do modelu pytają zawsze. Nie ma trybu, który to wyłącza.",
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
                        "Ten telefon nie ma jeszcze ustawionej blokady ekranu. Bez niej konto otworzy się bez drugiego składnika."
                    Biometrics.State.None ->
                        "To urządzenie nie obsługuje potwierdzania tożsamości, więc drugi składnik nie zadziała."
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
            Spacer(Modifier.height(4.dp))
            Spacer(Modifier.height(10.dp))
            LockAfterPicker()
            Spacer(Modifier.height(14.dp))
            Toggle("Potwierdzaj zakupy", Prefs.confirmBuy) { Prefs.setConfirmBuy(it) }
            Spacer(Modifier.height(14.dp))
            if (!SecureStore.encrypted) {
                Lead(
                    "Uwaga: szyfrowany schowek nie wstał na tym urządzeniu. Token sesji i hasło SSH " +
                        "są trzymane tylko w pamięci i znikną po zamknięciu aplikacji — nie trafiają " +
                        "na dysk bez szyfrowania. Trzeba będzie logować się za każdym razem.",
                )
                Spacer(Modifier.height(14.dp))
            }
            Toggle("Blokuj zrzuty ekranu w całej aplikacji", Prefs.secureScreen) { Prefs.setSecureScreen(it) }
            Spacer(Modifier.height(4.dp))
            Lead("Logowanie i rejestracja są chronione zawsze — tam zrzut wyniósłby hasło poza telefon. To ustawienie dotyczy pozostałych ekranów.")
        }

        Group("Przeglądarka") {
            Field(home, "Strona startowa", { home = it })
            Spacer(Modifier.height(10.dp))
            GhostButton("Zapisz stronę") { Prefs.setHomePage(home); home = Prefs.homePage }
            Spacer(Modifier.height(12.dp))
            Toggle("Widok komputera", Prefs.desktopMode) { Prefs.setDesktopMode(it) }
            Spacer(Modifier.height(12.dp))
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

        Group("Agent") {
            Lead(agentName ?: "Nie wybrano agenta.")
            Spacer(Modifier.height(14.dp))
            Field(prompt, "Instrukcja dla modelu", { prompt = it }, lines = 7)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Zapisz", Modifier.weight(1f)) {
                    Prefs.setSystemPrompt(prompt); prompt = Prefs.systemPrompt
                }
                GhostButton("Domyślna", Modifier.weight(1f)) {
                    Prefs.setSystemPrompt(Prefs.DEFAULT_PROMPT); prompt = Prefs.systemPrompt
                }
            }
            Spacer(Modifier.height(8.dp))
            Lead(
                "Ten tekst idzie do modelu przed każdą rozmową, jako wiadomość systemowa.\n\n" +
                    "Dosłownie wysyłanych jest ostatnie $KEEP_VERBATIM wiadomości. Starsze, gdy uzbiera " +
                    "się ich ponad $FOLD_ABOVE tokenów, są raz zwijane w notatkę z ustaleniami, " +
                    "potwierdzonymi wynikami i nieudanymi podejściami — i dalej idzie już tylko ona. " +
                    "Dzięki temu długa rozmowa nie drożeje z każdą wiadomością.",
            )
        }

        Group("Narzędzia") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_terminal), null, tint = Paper)
                Spacer(Modifier.width(12.dp))
                GhostButton("Terminal", onClick = onTerminal)
            }
        }

        GhostButton("Wyloguj się", onClick = onLogout)
        Spacer(Modifier.height(16.dp))
        Lead("CYPHR ${BuildConfig.VERSION_NAME}")
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
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Paper else Ink)
                    .border(1.5.dp, if (on) Paper else Line, RoundedCornerShape(50))
                    .clickable { Prefs.setLockAfter(seconds) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) Ink else Mist,
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
