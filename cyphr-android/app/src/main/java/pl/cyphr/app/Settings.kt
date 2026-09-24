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

const val DAY_SECONDS = 24 * 60 * 60

object Prefs {
    private const val FILE = "cyphr"
    private lateinit var app: Context

    const val DEFAULT_PROMPT_PL =
        "Jesteś agentem w aplikacji CYPHR. Odpowiadasz po polsku, zwięźle i rzeczowo.\n" +
            "Dostajesz pełną historię rozmowy — czytaj ją i trzymaj się wątku, nie zaczynaj od nowa " +
            "przy każdej wiadomości i nie powtarzaj tego, co już zostało ustalone.\n" +
            "Gdy pytanie odnosi się do czegoś wcześniejszego, odnieś się do tego wprost.\n" +
            "Jeśli czegoś nie wiesz albo brakuje Ci danych, powiedz to zamiast zgadywać."

    const val DEFAULT_PROMPT_EN =
        "You are an agent in the CYPHR app. Reply in the language the user writes in, concisely and to the point.\n" +
            "You get the full conversation history — read it and stay on topic; don't start over " +
            "with every message and don't repeat what has already been settled.\n" +
            "When a question refers to something earlier, address it directly.\n" +
            "If you don't know something or lack the data, say so instead of guessing."

    /** Domyslna instrukcja w jezyku aplikacji. */
    val defaultPrompt: String get() = tr(DEFAULT_PROMPT_PL, DEFAULT_PROMPT_EN)

    /** Czy to ktoras z domyslnych instrukcji (po polsku albo po angielsku) — takich nie zapisujemy. */
    fun isDefaultPrompt(value: String): Boolean = value.trim().let { it == DEFAULT_PROMPT_PL || it == DEFAULT_PROMPT_EN }

    private val _homePage = mutableStateOf<String>("https://duckduckgo.com")
    val homePage: String get() = _homePage.value
    private val _animations = mutableStateOf<Boolean>(true)
    val animations: Boolean get() = _animations.value
    private val _desktopMode = mutableStateOf<Boolean>(false)
    val desktopMode: Boolean get() = _desktopMode.value
    private val _agent = mutableStateOf<String?>(null)
    val agent: String? get() = _agent.value

    /**
     * Instrukcja wysylana jako wiadomosc "system" przed kazda rozmowa. Null — domyslna,
     * w jezyku aplikacji; zapisana zostaje tylko wlasna instrukcja uzytkownika.
     */
    private val _customPrompt = mutableStateOf<String?>(null)
    val systemPrompt: String get() = _customPrompt.value ?: defaultPrompt

    /** Jezyk wybrany recznie w Ustawieniach; null — automatycznie, po adresie IP. */
    private val _langChoice = mutableStateOf<Lang?>(null)
    val langChoice: Lang? get() = _langChoice.value

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

    /**
     * Po jakiej przerwie aplikacja otwiera sie na nowej, pustej rozmowie (0 — nigdy).
     * Domyslnie godzina: krotka przerwa wraca do tej samej rozmowy.
     */
    private val _newChatAfterSeconds = mutableStateOf(3600)
    val newChatAfterSeconds: Int get() = _newChatAfterSeconds.value

    /** Po ilu sekundach od ostatniego uzycia aplikacja znowu prosi o odcisk palca. Domyslnie 24 h. */
    private val _lockAfterSeconds = mutableStateOf<Int>(DAY_SECONDS)
    val lockAfterSeconds: Int get() = _lockAfterSeconds.value

    fun init(context: Context) {
        app = context.applicationContext
        val sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        forgetRemovedSettings(sp)
        _homePage.value = sp.getString("home_page", null) ?: "https://duckduckgo.com"
        _animations.value = sp.getBoolean("animations", true)
        _desktopMode.value = sp.getBoolean("desktop_mode", false)
        _agent.value = sp.getString("agent", null)
        _customPrompt.value = sp.getString("system_prompt", null)?.takeUnless { isDefaultPrompt(it) }
        _langChoice.value = Lang.of(sp.getString("lang", null))
        _askCommands.value = sp.getBoolean("ask_cmd", true)
        _agentTerminal.value = sp.getBoolean("agent_terminal", true)
        _appLock.value = sp.getBoolean("app_lock", true)
        _secureScreen.value = sp.getBoolean("secure_screen_all", false)
        _confirmBuy.value = sp.getBoolean("confirm_buy", true)
        _lockAfterSeconds.value = sp.getInt("lock_after", DAY_SECONDS)
        _newChatAfterSeconds.value = sp.getInt("new_chat_after", 3600)
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
    fun setNewChatAfter(seconds: Int) { _newChatAfterSeconds.value = seconds; edit { putInt("new_chat_after", seconds) } }

    private val sp get() = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Kiedy uzytkownik ostatnio wyszedl z aplikacji — do wyboru rozmowy po powrocie. */
    val lastSeen: Long? get() = sp.getLong("last_seen", 0L).takeIf { it > 0 }
    /** Rozmowa, ktora byla na ekranie przy wyjsciu. */
    val lastChat: String? get() = sp.getString("last_chat", null)
    /** Rozmowa z odpowiedzia, ktora przyszla, gdy aplikacja byla w tle. */
    val unseenChat: String? get() = sp.getString("unseen_chat", null)

    fun setLeft(now: Long, chatId: String) = edit { putLong("last_seen", now).putString("last_chat", chatId) }
    fun setUnseenChat(chatId: String?) = edit { if (chatId == null) remove("unseen_chat") else putString("unseen_chat", chatId) }

    /** Wersja Termuksa, o ktorego wymiane juz pytalismy — „Nie teraz” dotyczy tej wersji. */
    val termuxPrompted: String? get() = sp.getString("termux_prompted", null)
    fun setTermuxPrompted(version: String) = edit { putString("termux_prompted", version) }

    fun setSystemPrompt(value: String) {
        val custom = value.trim().takeUnless { it.isBlank() || isDefaultPrompt(it) }
        _customPrompt.value = custom
        edit { if (custom == null) remove("system_prompt") else putString("system_prompt", custom) }
    }

    fun setLangChoice(lang: Lang?) {
        _langChoice.value = lang
        edit { if (lang == null) remove("lang") else putString("lang", lang.code) }
    }

    /** Jezyk ustalony ostatnio po adresie IP i kiedy. */
    val langByIp: Lang? get() = Lang.of(sp.getString("lang_ip", null))
    val langByIpAt: Long get() = sp.getLong("lang_ip_at", 0L)
    fun setLangByIp(lang: Lang, at: Long) = edit { putString("lang_ip", lang.code).putLong("lang_ip_at", at) }

    /** Czy aplikacja juz raz zapytala o powiadomienia — odmowy nie ponawiamy. */
    val notificationsAsked: Boolean
        get() = app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("notifications_asked", false)

    fun setNotificationsAsked() = edit { putBoolean("notifications_asked", true) }

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
        SectionTitle(tr("Ustawienia", "Settings"), Modifier.padding(top = 6.dp, bottom = 18.dp))

        Group(tr("Język", "Language")) {
            LanguagePicker()
        }

        Group(tr("Czat", "Chat")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("Model", "Model"), color = Mist)
                Text(agentName ?: tr("nie wybrano", "not chosen"), color = Paper, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            // Domyslna instrukcja zmienia jezyk razem z aplikacja — nieruszone pole idzie za nia.
            val lang = Lang.current
            LaunchedEffect(lang) { if (Prefs.isDefaultPrompt(prompt)) prompt = Prefs.systemPrompt }
            Field(prompt, tr("Twoje instrukcje dla modelu", "Your instructions for the model"), { prompt = it; promptSaved = false }, lines = 6)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(if (promptSaved) tr("Zapisano", "Saved") else tr("Zapisz", "Save"), Modifier.weight(1f)) {
                    Prefs.setSystemPrompt(prompt); prompt = Prefs.systemPrompt; promptSaved = true
                }
                GhostButton(tr("Domyślne", "Default"), Modifier.weight(1f)) {
                    Prefs.setSystemPrompt(""); prompt = Prefs.systemPrompt; promptSaved = false
                }
            }
            Spacer(Modifier.height(10.dp))
            Lead(
                tr(
                    "Model bierze je pod uwagę w każdej rozmowie. Długie rozmowy są same streszczane, " +
                        "żeby każda kolejna wiadomość nie kosztowała coraz więcej.",
                    "The model takes them into account in every chat. Long chats are summarized " +
                        "automatically, so each new message doesn't cost more and more.",
                ),
            )
            Spacer(Modifier.height(16.dp))
            NewChatPicker()
        }

        Group(tr("Bezpieczeństwo", "Security")) {
            val bio = Biometrics.state(LocalContext.current)
            val method = (bio as? Biometrics.State.Ready)?.instrumental ?: tr("blokadą ekranu", "your screen lock")
            Lead(
                when (bio) {
                    is Biometrics.State.Ready -> tr(
                        "Drugi składnik logowania na tym telefonie: $method. Po zalogowaniu kontem Google albo hasłem konto otwiera się dopiero po potwierdzeniu i tego nie da się wyłączyć.",
                        "Second sign-in factor on this phone: $method. After signing in with Google or a password, the account opens only after you confirm — and this can't be turned off.",
                    )
                    Biometrics.State.NotEnrolled -> tr(
                        "Ten telefon nie ma jeszcze ustawionej blokady ekranu. Bez niej nie da się zalogować " +
                            "ani przełączyć konta — drugi składnik jest obowiązkowy.",
                        "This phone has no screen lock set up yet. Without it you can't sign in " +
                            "or switch accounts — the second factor is mandatory.",
                    )
                    Biometrics.State.None -> tr(
                        "To urządzenie nie obsługuje potwierdzania tożsamości, więc nowe logowanie nie przejdzie.",
                        "This device can't confirm your identity, so a new sign-in won't go through.",
                    )
                    Biometrics.State.Unavailable ->
                        tr("Czytnik jest chwilowo niedostępny.", "The sensor is temporarily unavailable.")
                },
            )
            if (bio is Biometrics.State.NotEnrolled) {
                Spacer(Modifier.height(12.dp))
                val ctx = LocalContext.current
                GhostButton(tr("Ustaw blokadę ekranu", "Set up screen lock")) { ctx.startActivity(Biometrics.enrollIntent()) }
            }
            Spacer(Modifier.height(14.dp))
            Toggle(tr("Pytaj o odcisk przy otwieraniu", "Ask for fingerprint when opening"), Prefs.appLock) { Prefs.setAppLock(it) }
            Spacer(Modifier.height(14.dp))
            LockAfterPicker()
            Spacer(Modifier.height(14.dp))
            Toggle(tr("Potwierdzaj zakupy", "Confirm purchases"), Prefs.confirmBuy) { Prefs.setConfirmBuy(it) }
            Spacer(Modifier.height(14.dp))
            if (!SecureStore.encrypted) {
                Lead(
                    tr(
                        "Uwaga: szyfrowany schowek nie wstał na tym urządzeniu. Token sesji jest trzymany " +
                            "tylko w pamięci i zniknie po zamknięciu aplikacji — nie trafia na dysk bez " +
                            "szyfrowania. Trzeba będzie logować się za każdym razem.",
                        "Note: the encrypted storage didn't start on this device. The session token is kept " +
                            "in memory only and disappears when the app closes — it never goes to disk " +
                            "unencrypted. You'll have to sign in every time.",
                    ),
                )
                Spacer(Modifier.height(14.dp))
            }
            Toggle(tr("Blokuj zrzuty ekranu w całej aplikacji", "Block screenshots in the whole app"), Prefs.secureScreen) { Prefs.setSecureScreen(it) }
            Spacer(Modifier.height(4.dp))
            Lead(
                tr(
                    "Logowanie i rejestracja są chronione zawsze — tam zrzut wyniósłby hasło poza telefon. To ustawienie dotyczy pozostałych ekranów.",
                    "Sign-in and sign-up are always protected — a screenshot there would carry your password off the phone. This setting covers the other screens.",
                ),
            )
        }

        Group(tr("Przeglądarka", "Browser")) {
            Field(home, tr("Strona startowa", "Home page"), { home = it; homeSaved = false })
            Spacer(Modifier.height(10.dp))
            GhostButton(if (homeSaved) tr("Zapisano", "Saved") else tr("Zapisz stronę", "Save page")) {
                Prefs.setHomePage(home); home = Prefs.homePage; homeSaved = true
            }
            Spacer(Modifier.height(14.dp))
            Toggle(tr("Widok komputera", "Desktop view"), Prefs.desktopMode) { Prefs.setDesktopMode(it) }
            Spacer(Modifier.height(14.dp))
            GhostButton(if (cleared) tr("Wyczyszczono", "Cleared") else tr("Wyczyść ciasteczka i pamięć", "Clear cookies and storage")) {
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                cleared = true
            }
        }

        Group(tr("Wygląd", "Appearance")) {
            Toggle(tr("Animacje", "Animations"), Prefs.animations) { Prefs.setAnimations(it) }
            Spacer(Modifier.height(4.dp))
            Lead(
                tr(
                    "Wyłączone animacje zatrzymują wszystkie ruchy: duszka, kartę, przejścia i dymki.",
                    "With animations off, everything stops moving: the ghost, the card, transitions and bubbles.",
                ),
            )
        }

        Group(tr("Terminal", "Terminal")) {
            TermuxSetup()
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Line, thickness = 1.dp)
            Spacer(Modifier.height(16.dp))
            Toggle(tr("Model może wykonywać polecenia", "The model can run commands"), Prefs.agentTerminal) { Prefs.setAgentTerminal(it) }
            Spacer(Modifier.height(6.dp))
            Lead(
                tr(
                    "Model może poprosić o uruchomienie polecenia w terminalu i przeczytać wynik. " +
                        "Każde polecenie potwierdzasz osobno i możesz odmówić. " +
                        "Idzie do Termuxa, jeśli jest połączony, inaczej do powłoki Androida.",
                    "The model can ask to run a command in the terminal and read the result. " +
                        "You confirm each command separately and can refuse. " +
                        "It goes to Termux if it's connected, otherwise to the Android shell.",
                ),
            )
            Spacer(Modifier.height(16.dp))
            Toggle(tr("Pytaj przed każdym poleceniem", "Ask before every command"), Prefs.askCommands) { Prefs.setAskCommands(it) }
            Spacer(Modifier.height(6.dp))
            Lead(
                tr(
                    "Zapis pliku, polecenia groźne (rm, mv, chmod, dd, przekierowania) oraz wysłanie " +
                        "treści strony do modelu pytają zawsze. Nie ma trybu, który to wyłącza.",
                    "Saving a file, dangerous commands (rm, mv, chmod, dd, redirections) and sending " +
                        "a page's content to the model always ask. There's no mode that turns this off.",
                ),
            )
            Spacer(Modifier.height(16.dp))
            GhostButton(tr("Otwórz terminal", "Open terminal"), onClick = onTerminal)
        }

        GhostButton(tr("Wyloguj się", "Sign out"), onClick = onLogout)
        Spacer(Modifier.height(18.dp))
        Lead("CYPHR ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth(), center = true)
        Spacer(Modifier.height(30.dp))
    }
}

/**
 * Po jakim czasie od ostatniego uzycia aplikacja znowu prosi o odcisk. Dotyczy wylacznie
 * powrotu do otwartej sesji — nowe logowanie potwierdza sie odciskiem zawsze.
 */
@Composable
private fun LockAfterPicker() {
    ChoiceChips(
        title = tr("Pyta ponownie po", "Asks again after"),
        options = listOf(
            60 to "1 min",
            900 to "15 min",
            3600 to tr("1 godz.", "1 h"),
            DAY_SECONDS to tr("24 godz.", "24 h"),
            3 * DAY_SECONDS to tr("3 dni", "3 days"),
        ),
        selected = Prefs.lockAfterSeconds,
        onPick = { Prefs.setLockAfter(it) },
        note = tr(
            "Liczy się od ostatniego użycia aplikacji — także po jej zamknięciu. Nowe logowanie " +
                "i wejście na inne konto potwierdzasz odciskiem zawsze, niezależnie od tego ustawienia.",
            "Counted from the last time you used the app — also after closing it. A new sign-in " +
                "and switching to another account always need your fingerprint, whatever this is set to.",
        ),
    )
}

/** Po jakiej przerwie aplikacja otwiera sie na nowej rozmowie. */
@Composable
private fun NewChatPicker() {
    ChoiceChips(
        title = tr("Nowa rozmowa po przerwie", "New chat after a break"),
        options = listOf(
            0 to tr("Nigdy", "Never"),
            1800 to "30 min",
            3600 to tr("1 godz.", "1 h"),
            DAY_SECONDS to tr("24 godz.", "24 h"),
        ),
        selected = Prefs.newChatAfterSeconds,
        onPick = { Prefs.setNewChatAfter(it) },
        note = tr(
            "Po takiej przerwie CYPHR otwiera się na pustej rozmowie, a poprzednie czekają na liście. " +
                "Po krótszej wracasz do ostatniej rozmowy. Odpowiedź, która przyszła w tle, otwiera się zawsze.",
            "After a break this long, CYPHR opens on an empty chat and the previous ones wait in the list. " +
                "After a shorter one you're back in your last chat. A reply that arrived in the background always opens.",
        ),
    )
}

/**
 * Jezyk aplikacji: automatycznie po adresie IP (polski dla polskich adresow, angielski dla
 * pozostalych) albo na stale wybrany tutaj.
 */
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    ChoiceChips(
        title = tr("Język aplikacji", "App language"),
        options = listOf(0 to tr("Według IP", "By IP"), 1 to "Polski", 2 to "English"),
        selected = when (Prefs.langChoice) { null -> 0; Lang.PL -> 1; Lang.EN -> 2 },
        onPick = { LangPick.choose(context, when (it) { 1 -> Lang.PL; 2 -> Lang.EN; else -> null }) },
        note = if (Prefs.langChoice == null) {
            tr(
                "Polski dla polskiego adresu IP, angielski dla pozostałych. Sprawdzane przy każdym uruchomieniu.",
                "Polish for a Polish IP address, English for all others. Checked every time the app starts.",
            )
        } else {
            tr(
                "Ustawiony na stałe — adres IP nie zmienia języka.",
                "Set permanently — your IP address doesn't change the language.",
            )
        },
    )
}

/** Rzad przyciskow z jednym wybranym — plynnie zmienia kolory przy wyborze. */
@Composable
private fun ChoiceChips(title: String, options: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit, note: String) {
    Text(title, color = Mist, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = selected == value
            val bg by androidx.compose.animation.animateColorAsState(if (on) Paper else Ink, motionSpec(220), label = "chipBg")
            val edge by androidx.compose.animation.animateColorAsState(if (on) Paper else Line, motionSpec(220), label = "chipEdge")
            val fg by androidx.compose.animation.animateColorAsState(if (on) Ink else Mist, motionSpec(220), label = "chipFg")
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .border(1.5.dp, edge, RoundedCornerShape(50))
                    .clickable { onPick(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = fg,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Lead(note)
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
        // Dluzszy opis zawija sie przed przelacznikiem, zamiast na niego nachodzic.
        Text(label, color = if (enabled) Paper else Mist, modifier = Modifier.weight(1f).padding(end = 12.dp))
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
