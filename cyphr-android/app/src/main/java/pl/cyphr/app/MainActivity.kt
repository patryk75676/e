package pl.cyphr.app

import android.os.Build
import android.content.pm.PackageManager
import android.content.Context
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Route { Splash, Auth, Verify, Reset, Main, Terminal, Settings }

/** Jak gleboko lezy ekran. Wejscie glebiej wjezdza z prawej, powrot — z lewej. */
private val Route.depth: Int
    get() = when (this) {
        Route.Splash -> 0
        Route.Auth, Route.Main -> 1
        Route.Verify, Route.Reset, Route.Settings -> 2
        Route.Terminal -> 3
    }

/**
 * Ekrany z hasłem i kodem są zawsze chronione przed zrzutem ekranu, niezależnie
 * od ustawienia — tam zrzut wyniósłby poświadczenia poza telefon.
 */
@Composable
private fun SecureWindow(secure: Boolean) {
    val context = LocalContext.current
    DisposableEffect(secure) {
        val window = (context as? Activity)?.window
        if (secure) {
            window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { }
    }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Okna zgody (Termux, powiadomienia) musza byc zarejestrowane przed startem aktywnosci.
        TermuxPermission.register(this)
        NotificationPermission.register(this)
        enableEdgeToEdge()
        SecureStore.init(this)
        Prefs.init(this)
        // Jezyk od pierwszej klatki: wybrany recznie albo ostatnio ustalony po IP.
        LangPick.apply(this)
        Api.load(this)
        ChatEngine.init(this)
        // Logowanie Google idzie wylacznie natywnym wyborem konta. Dawny powrot
        // z przegladarki (cyphr://google?id_token=...) przyjmowal token od dowolnej
        // strony albo aplikacji — mogla zalogowac telefon na cudze konto. Usuniety.
        setContent { CyphrTheme { CyphrGate(this) } }
        // Po odtworzeniu ekranu ten sam „Udostepnij” przyszedlby drugi raz.
        if (savedInstanceState == null) receiveShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShared(intent)
    }

    /**
     * „Udostepnij → CYPHR” z innej aplikacji: zrzut ekranu, zdjecie, PDF albo tekst trafia
     * do pola wpisywania. Nic nie jest wysylane samo — nawet przy zablokowanej aplikacji
     * tylko czeka, az uzytkownik sam wysle.
     */
    private fun receiveShared(intent: Intent?) {
        if (intent == null) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamOf(intent))
            Intent.ACTION_SEND_MULTIPLE -> streamsOf(intent)
            else -> return
        }
        Composer.add(this, uris, fromShare = true)
        // CharSequence, nie String: czesc aplikacji wysyla tekst z formatowaniem.
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { Composer.share(it.take(20_000)) }
        // Jednorazowo: ten sam intent nie moze dodac plikow drugi raz.
        setIntent(Intent(this, MainActivity::class.java))
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamsOf(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

    override fun onStart() {
        super.onStart()
        // Ekran widac — wynik odpowiedzi pokaze sie w rozmowie, powiadomienie jest zbedne.
        ChatEngine.visible = true
        Notifications.clear(this)
        // Ktora rozmowa po powrocie: z prosba o zgode, z odpowiedzia z tla, nowa po dluzszej
        // przerwie albo ta sama, co przy wyjsciu.
        ChatEngine.land(
            Landing.decide(
                now = System.currentTimeMillis(),
                lastSeen = Prefs.lastSeen,
                lastChat = Prefs.lastChat,
                unseen = Prefs.unseenChat,
                pending = ChatEngine.approval.value?.chatId,
                afterSeconds = Prefs.newChatAfterSeconds,
            ),
        )
        Prefs.setUnseenChat(null)
        ChatEngine.refreshImages()
        // Po dluzszej przerwie telefon mogl zmienic siec — jezyk po IP sprawdzamy jeszcze raz.
        if (LangPick.stale()) LangPick.refresh(this)
    }

    override fun onStop() {
        ChatEngine.visible = false
        Prefs.setLeft(System.currentTimeMillis(), ChatEngine.activeId.value)
        super.onStop()
    }
}

/**
 * Prosba o drugi skladnik przy nowym logowaniu albo wejsciu na inne zapamietane konto.
 * [onFail] dostaje powod od systemu albo null, gdy uzytkownik sam zrezygnowal.
 */
private class SecondFactor(val reason: String, val onOk: () -> Unit, val onFail: (String?) -> Unit)

/**
 * Brama aplikacji. Drugi skladnik logowania: system pyta tym, co ma dany telefon,
 * czyli odciskiem palca, twarza albo kodem ekranu blokady.
 */
@Composable
private fun CyphrGate(activity: FragmentActivity) {
    var bio by remember { mutableStateOf(Biometrics.state(activity)) }
    val hasLock = bio is Biometrics.State.Ready
    // Po starcie pytamy tylko, gdy od ostatniego uzycia minelo wiecej niz okno blokady
    // (domyslnie 24 h) — wczesniej odcisk byl przy kazdym uruchomieniu.
    var unlocked by remember { mutableStateOf(Api.token() == null || !hasLock || !LockClock.due()) }
    var problem by remember { mutableStateOf<String?>(null) }

    // Logowanie czekajace, az telefon bedzie mial czym potwierdzic tozsamosc.
    var pending by remember { mutableStateOf<SecondFactor?>(null) }

    fun ask(reason: String) {
        Biometrics.prompt(
            activity = activity,
            subtitle = reason,
            onSuccess = { unlocked = true; problem = null; LockClock.touch() },
            onFailure = { problem = it },
        )
    }

    /**
     * Drugi skladnik, ktorego nie da sie ominac. Wczesniej telefon bez blokady ekranu
     * wpuszczal na konto bez niego — czyli wystarczylo zdjac blokade, zeby wylaczyc 2FA.
     * Teraz logowanie czeka na ekranie „Ustaw blokade” i rusza samo po powrocie.
     */
    fun confirm(request: SecondFactor) {
        val state = Biometrics.state(activity)
        bio = state
        if (state is Biometrics.State.Ready) {
            pending = null
            Biometrics.prompt(
                activity,
                request.reason,
                // Swiezo potwierdzone logowanie tez liczy sie jako odblokowanie.
                onSuccess = { LockClock.touch(); request.onOk() },
                onFailure = request.onFail,
            )
        } else {
            pending = request
        }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                // Czas wyjscia liczymy tylko przy odblokowanej aplikacji — patrz LockClock.touch.
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> if (unlocked && Api.token() != null) LockClock.touch()
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    // Stan moze sie zmienic, gdy ktos w miedzyczasie dopisal odcisk w ustawieniach
                    bio = Biometrics.state(activity)
                    // Powrot z ustawien z gotowa blokada: czekajace logowanie rusza dalej.
                    pending?.let { if (bio is Biometrics.State.Ready) confirm(it) }
                    if (bio is Biometrics.State.Ready && unlocked && Api.token() != null && LockClock.due()) {
                        unlocked = false
                    }
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(unlocked) { if (!unlocked) ask(tr("Potwierdź, że to Ty", "Confirm it's you")) }

    if (unlocked) {
        // Nakladka zamiast podmiany ekranu: CyphrApp zostaje w pamieci, wiec nie
        // traci wpisanego e-maila ani nie odtwarza sesji z juz zapisanego tokenu.
        Box(Modifier.fillMaxSize()) {
            CyphrApp(
                // Tylko potwierdzanie zakupu — to da sie wylaczyc, wiec bez blokady przechodzi.
                requireFingerprint = { reason, onOk ->
                    if (Biometrics.state(activity) !is Biometrics.State.Ready) onOk()
                    else Biometrics.prompt(activity, reason, onSuccess = onOk)
                },
                requireSecondFactor = { reason, onOk, onFail -> confirm(SecondFactor(reason, onOk, onFail)) },
            )
            pending?.let { request ->
                LockRequired(
                    state = bio,
                    onSetUp = { activity.startActivity(Biometrics.enrollIntent()) },
                    onRetry = { confirm(request) },
                    onCancel = { pending = null; request.onFail(null) },
                )
            }
        }
    } else {
        val ready = bio as? Biometrics.State.Ready
        Column(
            Modifier.fillMaxSize().background(Ink).padding(horizontal = 26.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Ghost(size = 118.dp, floating = true)
            Spacer(Modifier.height(24.dp))
            Text(tr("CYPHR jest zablokowany", "CYPHR is locked"), color = Paper, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Lead(
                problem ?: ready?.let { tr("Drugi składnik logowania: ${it.label}.", "Second sign-in factor: ${it.label}.") }
                    ?: tr("Ustaw blokadę ekranu, żeby otworzyć konto.", "Set up a screen lock to open your account."),
                center = true,
            )
            Spacer(Modifier.height(22.dp))
            Box(Modifier.width(230.dp)) {
                if (bio is Biometrics.State.NotEnrolled) {
                    PrimaryButton(tr("Ustaw blokadę", "Set up lock")) { activity.startActivity(Biometrics.enrollIntent()) }
                } else {
                    PrimaryButton(tr("Odblokuj", "Unlock")) { ask(tr("Potwierdź, że to Ty", "Confirm it's you")) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(230.dp)) {
                GhostButton(tr("Wyloguj się", "Sign out")) {
                    Api.cachedUser()?.id?.let { ChatEngine.stop(it) }
                    Api.saveToken(activity, null)
                    LockClock.forget()
                    unlocked = true
                }
            }
        }
    }
}

/**
 * Ekran zamiast logowania, gdy telefon nie ma czym potwierdzic tozsamosci.
 * Drugi skladnik jest obowiazkowy, wiec jedyne wyjscia to ustawic blokade albo zrezygnowac.
 */
@Composable
private fun LockRequired(
    state: Biometrics.State,
    onSetUp: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .clickable(enabled = false) {}
            .padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Ghost(size = 110.dp, floating = true)
        Spacer(Modifier.height(24.dp))
        Text(tr("Potrzebna blokada ekranu", "Screen lock needed"), color = Paper, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        Lead(
            when (state) {
                Biometrics.State.None -> tr(
                    "To urządzenie nie potrafi potwierdzić tożsamości, a bez tego CYPHR nie otworzy konta.",
                    "This device can't confirm your identity, and without that CYPHR won't open the account.",
                )
                Biometrics.State.Unavailable -> tr(
                    "Czytnik jest chwilowo niedostępny. Spróbuj za moment.",
                    "The sensor is temporarily unavailable. Try again in a moment.",
                )
                else -> tr(
                    "Po zalogowaniu CYPHR prosi o drugi składnik — odcisk palca, twarz albo kod ekranu. " +
                        "Ten telefon nie ma jeszcze ustawionej blokady. Ustaw ją i wróć, logowanie ruszy samo.",
                    "After you sign in, CYPHR asks for a second factor — fingerprint, face or screen lock code. " +
                        "This phone has no screen lock yet. Set one up and come back — sign-in will continue by itself.",
                )
            },
            center = true,
        )
        Spacer(Modifier.height(22.dp))
        Box(Modifier.width(250.dp)) {
            if (state is Biometrics.State.NotEnrolled) PrimaryButton(tr("Ustaw blokadę", "Set up lock"), onClick = onSetUp)
            else PrimaryButton(tr("Spróbuj ponownie", "Try again"), onClick = onRetry)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(250.dp)) { GhostButton(tr("Anuluj logowanie", "Cancel sign-in"), onClick = onCancel) }
    }
}

@Composable
private fun CyphrApp(
    requireFingerprint: (String, () -> Unit) -> Unit = { _, ok -> ok() },
    requireSecondFactor: (String, () -> Unit, (String?) -> Unit) -> Unit = { _, ok, _ -> ok() },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var route by remember { mutableStateOf(Route.Splash) }
    SecureWindow(route in setOf(Route.Auth, Route.Verify, Route.Reset) || Prefs.secureScreen)

    var user by remember { mutableStateOf<User?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingEmail by remember { mutableStateOf("") }
    var cooldown by remember { mutableStateOf(0) }
    // Rosnie, gdy serwer odrzuci sam kod z maila — wtedy pole kodu sie czysci.
    var codeRejected by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(Tab.Chat) }
    var agents by remember { mutableStateOf<List<Agent>>(emptyList()) }
    var selectedAgent by remember { mutableStateOf(Prefs.agent) }
    var shop by remember { mutableStateOf<Shop?>(null) }
    var usage by remember { mutableStateOf<Usage?>(null) }
    var plan by remember { mutableStateOf<Plan?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableStateOf(0) }
    var accounts by remember { mutableStateOf(Accounts.all()) }

    // Czat. Rozmowy i odpowiedzi trzyma ChatEngine, a nie ten ekran — odpowiedz liczy sie
    // dalej, gdy wyjdziesz z aplikacji, zablokujesz ja albo ekran sie odtworzy.
    val chats by ChatEngine.chats.collectAsState()
    val activeId by ChatEngine.activeId.collectAsState()
    val busyChats by ChatEngine.busy.collectAsState()
    val lastTokens by ChatEngine.lastTokens.collectAsState()
    val approval by ChatEngine.approval.collectAsState()
    val drawingChats by ChatEngine.drawing.collectAsState()
    val queuedChats by ChatEngine.queued.collectAsState()
    val images by ChatEngine.images.collectAsState()
    val staged by Composer.staged.collectAsState()
    val importing by Composer.importing.collectAsState()
    val sharedText by Composer.sharedText.collectAsState()
    var showChats by remember { mutableStateOf(false) }

    // Rozmowy naleza do konta, nie do instalacji. Wczytujemy je, gdy wiadomo kto
    // jest zalogowany, i zostawiamy na dysku przy wylogowaniu.
    LaunchedEffect(user?.id) { user?.id?.let { ChatEngine.open(it) } }

    // Wiadomosci od odpowiedzi idacej w tle: blad do pokazania albo nowe saldo.
    LaunchedEffect(Unit) {
        ChatEngine.events.collect { event ->
            when (event) {
                is ChatEngine.Event.Message -> if (event.uid == user?.id) toast = event.text
                // Saldo tylko dla konta, ktore wciaz jest zalogowane.
                is ChatEngine.Event.Account -> if (event.user.id == user?.id) user = event.user
            }
        }
    }
    // Za duzy plik, zly typ, limit zalacznikow — krotko na dole ekranu.
    LaunchedEffect(Unit) { Composer.problems.collect { toast = it } }
    // Termux do wymiany — sprawdzane po wejsciu na glowny ekran, bez wysylania mu polecen.
    var termuxOld by remember { mutableStateOf<String?>(null) }
    var termuxSheet by remember { mutableStateOf(false) }
    LaunchedEffect(user?.id, route) {
        if (user == null || route != Route.Main || termuxOld != null || termuxSheet) return@LaunchedEffect
        val version = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Termux.needsReplacement(context) }
        if (version != null && version != Prefs.termuxPrompted) termuxOld = version
    }
    // „Udostepnij → CYPHR” przy otwartej aplikacji: prosto do czatu, gdzie czeka zalacznik.
    LaunchedEffect(Unit) {
        Composer.arrivals.collect {
            if (user != null && route in setOf(Route.Main, Route.Settings, Route.Terminal)) {
                route = Route.Main
                tab = Tab.Chat
            }
        }
    }

    val active = chats.firstOrNull { it.id == activeId } ?: chats.first()
    val messages = active.messages
    val memory = active.memory
    val thinking = active.id in busyChats
    // Na ekranie zawsze nazwa CYPHR — nigdy identyfikator, ktory idzie do serwera.
    val agentName = selectedAgent?.let { Persona.nameOf(it) }

    // Przegladarka. Osobna dla kazdego konta, zeby historia jednego nie przechodzila
    // na drugie; stara jest niszczona przy zmianie konta.
    val browser = remember(user?.id) { BrowserHolder() }
    DisposableEffect(browser) { onDispose { browser.destroy() } }
    var answer by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }

    // Sklep: blad pokazujemy na miejscu, zamiast wiecznego „Wczytuje pakiety”.
    var shopError by remember { mutableStateOf<String?>(null) }
    // /profile istnieje dopiero z paczka cyphr-app.zip. Po 404 nie pytamy go przy
    // kazdym wejsciu w Konto — to tylko zjadalo limit zapytan serwera.
    var profileMissing by remember { mutableStateOf(false) }
    var agentsLoadedAt by remember { mutableStateOf(0L) }

    // Zakup
    var buying by remember { mutableStateOf<Pack?>(null) }
    var bought by remember { mutableStateOf(false) }

    val googleClient = remember {
        GoogleSignIn.getClient(
            context,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .requestEmail()
                .build(),
        )
    }

    fun open(u: User) {
        user = u
        error = null
        route = Route.Main
    }

    /**
     * Serwer wydal juz token, ale bez drugiego skladnika logowanie sie nie odbylo.
     * Porzucamy te sesje takze na serwerze, zamiast zostawiac wazny token w schowku.
     */
    fun abandonLogin(u: User) {
        val abandoned = Api.token()
        // Lokalnie od razu — gdyby czyscic dopiero po odpowiedzi serwera, szybkie
        // ponowne logowanie zostaloby skasowane przez spoznione wylogowanie.
        Api.saveToken(context, null)
        Accounts.remove(u.id)
        accounts = Accounts.all()
        if (abandoned != null) scope.launch { Api.revoke(abandoned) }
    }

    /** Nowe logowanie przechodzi przez drugi składnik. Wznowienie sesji nie, bo brama już pytała. */
    fun enterApp(u: User) {
        requireSecondFactor(
            tr("Potwierdź logowanie", "Confirm sign-in"),
            { accounts = Accounts.all(); open(u) },
            { reason ->
                abandonLogin(u)
                route = Route.Auth
                error = reason ?: tr(
                    "Logowanie przerwane — bez potwierdzenia tożsamości konto się nie otworzy.",
                    "Sign-in stopped — the account won't open without confirming your identity.",
                )
            },
        )
    }

    /** Wejscie na zapamietane konto to tez logowanie — z drugim skladnikiem. */
    fun switchTo(acc: Account, onRefused: () -> Unit = {}) {
        requireSecondFactor(
            tr("Wejdź na ${acc.email}", "Open ${acc.email}"),
            {
                scope.launch {
                    Api.useAccount(context, acc)
                    user = Api.cachedUser()
                    usage = null; shop = null; plan = null
                    agents = emptyList(); ChatEngine.noteUsage(null)
                    accounts = Accounts.all()
                    tab = Tab.Chat
                    route = Route.Main
                    user = try { Api.me() } catch (e: Exception) { user }
                }
            },
            { reason ->
                reason?.let { toast = it }
                onRefused()
            },
        )
    }

    /**
     * Wylogowanie biezacego konta. Gdy na telefonie zostaly inne zapamietane konta,
     * proponujemy wejscie na kolejne — ale z odciskiem, a nie po cichu jak wczesniej.
     */
    fun logout() {
        scope.launch {
            busy = true
            val gone = user?.id
            // Odpowiedzi w tle tego konta koncza sie razem z nim — nie ida dalej z jego salda.
            gone?.let { ChatEngine.stop(it) }
            Api.logout(context)
            // Nastepna osoba na tym telefonie nie wchodzi „w oknie” poprzedniej.
            LockClock.forget()
            googleClient.signOut()
            gone?.let { Accounts.remove(it) }
            accounts = Accounts.all()
            usage = null; shop = null; plan = null
            agents = emptyList(); ChatEngine.noteUsage(null)
            busy = false
            // Rozmowy zostaja na dysku pod kontem — wroca po zalogowaniu.
            user = null
            val next = accounts.firstOrNull()
            if (next == null) {
                route = Route.Auth
            } else {
                route = Route.Splash
                switchTo(next) { route = Route.Auth }
            }
        }
    }

    suspend fun loadAgents() {
        busy = true
        // O tym, czym wolno rozmawiac, decyduje serwer. Katalog aplikacji dokłada
        // nazwy i wchodzi awaryjnie, gdy serwer nie odpowiada.
        val remote = try {
            Api.models().ifEmpty { Api.agents() }
        } catch (e: Exception) {
            try { Api.agents() } catch (e2: Exception) { emptyList() }
        }
        agents = mergeAgents(remote)
        if (remote.isNotEmpty()) agentsLoadedAt = System.currentTimeMillis()
        if (selectedAgent == null || agents.none { it.id == selectedAgent }) {
            selectedAgent = (agents.firstOrNull { it.id == DEFAULT_AGENT } ?: agents.firstOrNull())?.id
            selectedAgent?.let { Prefs.setAgent(it) }
        }
        busy = false
    }

    val googleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch {
            busy = true
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
                val idToken = account.idToken ?: throw ApiError(tr("Google nie zwrócił tokenu.", "Google didn't return a token."))
                Api.google(context, idToken)?.let { enterApp(it) }
            } catch (e: ApiException) {
                error = when (e.statusCode) {
                    GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> null
                    // Zamiast ogolnej porady pokazujemy wartosci odczytane z tej
                    // konkretnej paczki — to je Google porownuje z konsola, wiec
                    // da sie je zestawic pole po polu bez zgadywania.
                    CommonStatusCodes.DEVELOPER_ERROR -> tr(
                        "Kod 10: Google nie dopasował aplikacji do konsoli.\n\n" +
                            "W projekcie ${BuildConfig.GOOGLE_WEB_CLIENT_ID.substringBefore('-')} " +
                            "muszą być dwa klienty OAuth:\n\n" +
                            "Android — pakiet ${context.packageName}, SHA-1:\n${podpisSha1(context)}\n\n" +
                            "Aplikacja internetowa — to jej identyfikator wysyła aplikacja:\n" +
                            "${BuildConfig.GOOGLE_WEB_CLIENT_ID}\n\n" +
                            "Ten drugi nie może być klientem Android. Nowe wpisy Google " +
                            "propaguje od 5 minut do kilku godzin.",
                        "Code 10: Google didn't match the app to the console.\n\n" +
                            "Project ${BuildConfig.GOOGLE_WEB_CLIENT_ID.substringBefore('-')} " +
                            "needs two OAuth clients:\n\n" +
                            "Android — package ${context.packageName}, SHA-1:\n${podpisSha1(context)}\n\n" +
                            "Web application — the app sends this one's ID:\n" +
                            "${BuildConfig.GOOGLE_WEB_CLIENT_ID}\n\n" +
                            "The second one can't be an Android client. Google takes " +
                            "from 5 minutes to a few hours to apply new entries.",
                    )
                    CommonStatusCodes.NETWORK_ERROR -> tr("Brak połączenia z Google.", "Can't reach Google.")
                    GoogleSignInStatusCodes.SIGN_IN_FAILED -> tr(
                        "Google odrzucił logowanie. Upewnij się, że ekran zgody OAuth " +
                            "jest skonfigurowany w tym samym projekcie.",
                        "Google rejected the sign-in. Make sure the OAuth consent screen " +
                            "is set up in the same project.",
                    )
                    else -> tr(
                        "Logowanie Google nie powiodło się (kod ${e.statusCode}).",
                        "Google sign-in failed (code ${e.statusCode}).",
                    )
                }
            } catch (e: Exception) {
                error = e.message
            } finally { busy = false }
        }
    }

    LaunchedEffect(Unit) {
        val started = System.currentTimeMillis()
        // Jezyk po adresie IP: pytanie idzie od razu, rownolegle z odtwarzaniem sesji.
        val geo = LangPick.refresh(context)
        var restored: User? = null
        if (Api.token() != null) {
            restored = try { Api.me() } catch (e: Exception) {
                if (e is ApiError && (e.status == 401 || e.status == 403)) {
                    // Token naprawde wygasl — trzeba zalogowac sie od nowa.
                    Api.saveToken(context, null)
                    null
                } else {
                    // Serwer nie odpowiada, ale token jest wazny. Wpuszczamy na ostatnio
                    // znanym profilu, zamiast udawac wylogowanie.
                    Api.cachedUser()
                }
            }
        }
        val left = motion(1300) - (System.currentTimeMillis() - started)
        if (left > 0) delay(left)
        // Pierwsze uruchomienie: logowanie od razu we wlasciwym jezyku. Na serwer czekamy
        // najwyzej 2,5 s od startu — potem zostaje jezyk sieci komorkowej albo telefonu.
        if (!LangPick.knowsIp && Prefs.langChoice == null) {
            kotlinx.coroutines.withTimeoutOrNull((2500 - (System.currentTimeMillis() - started)).coerceAtLeast(1)) { geo.await() }
        }
        if (restored != null) open(restored) else route = Route.Auth
    }

    LaunchedEffect(cooldown) { if (cooldown > 0) { delay(1000); cooldown -= 1 } }
    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }

    LaunchedEffect(route, tab) {
        if (route != Route.Main) return@LaunchedEffect
        when (tab) {
            // Lista modeli zmienia sie rzadko — odswiezamy co kilka minut albo przyciskiem,
            // a nie przy kazdym dotknieciu zakladki.
            Tab.Agents -> if (agents.isEmpty() || System.currentTimeMillis() - agentsLoadedAt > 5 * 60_000) loadAgents()
            Tab.Chat -> if (agents.isEmpty()) loadAgents()
            Tab.Shop -> {
                shopError = null
                shop = try { Api.shop() } catch (e: Exception) { shopError = e.message; null }
            }
            Tab.Account -> {
                usage = try { Api.usage() } catch (e: Exception) { null }
                usage?.let { u -> user = user?.copy(balanceUsd = u.balanceUsd) }
                if (!profileMissing) {
                    try {
                        val profile = Api.profile()
                        plan = profile.plan
                        user = profile.user
                    } catch (e: Exception) {
                        plan = null
                        if (e is ApiError && e.status == 404) profileMissing = true
                    }
                }
            }
            Tab.Browser -> Unit
        }
    }

    BackHandler(
        enabled = route == Route.Terminal || route == Route.Settings || route == Route.Reset ||
            route == Route.Verify || (route == Route.Main && tab != Tab.Chat),
    ) {
        when {
            route == Route.Terminal -> route = Route.Settings
            route == Route.Settings -> route = Route.Main
            // Z kodu wracamy do logowania, a nie z aplikacji.
            route == Route.Reset || route == Route.Verify -> { error = null; route = Route.Auth }
            else -> tab = Tab.Chat
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val from = initialState.depth
                val to = targetState.depth
                when {
                    // Glebiej: nowy ekran wjezdza z prawej, stary lekko cofa sie w lewo.
                    to > from && from > 0 ->
                        (slideInHorizontally(motionSpec(380)) { it / 3 } + fadeIn(motionSpec(300)))
                            .togetherWith(slideOutHorizontally(motionSpec(380)) { -it / 10 } + fadeOut(motionSpec(200)))
                    // Wstecz: odwrotnie.
                    to < from && to > 0 ->
                        (slideInHorizontally(motionSpec(380)) { -it / 10 } + fadeIn(motionSpec(300)))
                            .togetherWith(slideOutHorizontally(motionSpec(380)) { it / 3 } + fadeOut(motionSpec(200)))
                    // Start, logowanie, wylogowanie: miekkie przenikanie z lekkim przyblizeniem.
                    else ->
                        (fadeIn(motionSpec(420)) + scaleIn(motionSpec(420), initialScale = 0.97f))
                            .togetherWith(fadeOut(motionSpec(220)))
                }
            },
            label = "route",
        ) { current ->
            when (current) {
                Route.Splash -> SplashScreen()

                Route.Auth -> AuthScreen(
                    busy = busy,
                    error = error,
                    initialEmail = pendingEmail,
                    saved = accounts,
                    onUseSaved = { acc -> error = null; switchTo(acc) },
                    onGoogle = {
                        error = null
                        // Tylko natywny wybor konta — zadnej przegladarki. signOut()
                        // przed startem wymusza liste kont zamiast cichego wejscia
                        // na ostatnio uzyte.
                        googleClient.signOut().addOnCompleteListener {
                            googleLauncher.launch(googleClient.signInIntent)
                        }
                    },
                    onForgot = { email ->
                        // Samo pole e-mail musi byc sensowne — reszta dzieje sie na serwerze.
                        error = if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email)) {
                            tr("Wpisz swój adres e-mail, wyślemy na niego kod.", "Enter your email address and we'll send you a code.")
                        } else {
                            null
                        }
                        if (error == null) scope.launch {
                            busy = true
                            try {
                                Api.forgot(email)
                                pendingEmail = email
                                cooldown = 60
                                route = Route.Reset
                            } catch (e: Exception) {
                                error = e.message
                            } finally { busy = false }
                        }
                    },
                    onSubmit = { register, email, password, name ->
                        error = validate(register, email, password)
                        if (error == null) scope.launch {
                            busy = true
                            try {
                                val result = if (register) Api.register(context, email, password, name)
                                else Api.login(context, email, password)
                                if (result != null) enterApp(result)
                                else { pendingEmail = email; cooldown = 60; route = Route.Verify }
                            } catch (e: Exception) {
                                if (e is ApiError && e.code == "not_verified") {
                                    pendingEmail = email; cooldown = 60; route = Route.Verify
                                } else {
                                    // Blad spoza naszego API ma nieprzetlumaczony komunikat,
                                    // wiec dokladamy typ wyjatku — inaczej nie wiadomo co sie stalo.
                                    error = if (e is ApiError) e.message
                                    else "${e.javaClass.simpleName}: ${e.message ?: tr("brak szczegółów", "no details")}"
                                }
                            } finally { busy = false }
                        }
                    },
                )

                Route.Verify -> VerifyScreen(
                    email = pendingEmail,
                    busy = busy,
                    error = error,
                    cooldown = cooldown,
                    codeRejected = codeRejected,
                    onBack = { error = null; route = Route.Auth },
                    onResend = {
                        scope.launch {
                            busy = true
                            try { Api.resend(pendingEmail); cooldown = 60; toast = tr("Wysłaliśmy nowy kod.", "We've sent a new code.") }
                            catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    },
                    onSubmit = { code ->
                        if (code.length != 6) error = tr("Wpisz wszystkie 6 cyfr.", "Enter all 6 digits.")
                        else scope.launch {
                            busy = true
                            try { Api.verify(context, pendingEmail, code)?.let { enterApp(it) } }
                            catch (e: Exception) {
                                error = e.message
                                val retryable = e is ApiError && (e.status == 429 || e.code == "offline" || e.status >= 500)
                                if (!retryable) codeRejected++
                            }
                            finally { busy = false }
                        }
                    },
                )

                Route.Reset -> ResetScreen(
                    email = pendingEmail,
                    busy = busy,
                    error = error,
                    cooldown = cooldown,
                    codeRejected = codeRejected,
                    onBack = { error = null; route = Route.Auth },
                    onResend = {
                        scope.launch {
                            busy = true
                            try { Api.forgot(pendingEmail); cooldown = 60; toast = tr("Wysłaliśmy nowy kod.", "We've sent a new code.") }
                            catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    },
                    onSubmit = { code, haslo ->
                        error = when {
                            code.length != 6 -> tr("Wpisz wszystkie 6 cyfr.", "Enter all 6 digits.")
                            haslo.length < 8 -> tr("Hasło musi mieć co najmniej 8 znaków.", "The password must be at least 8 characters.")
                            else -> null
                        }
                        if (error == null) scope.launch {
                            busy = true
                            try {
                                // Zmiana hasla i od razu zwykle logowanie nowym haslem —
                                // z odciskiem palca, tak samo jak przy wejsciu z ekranu
                                // logowania.
                                Api.reset(pendingEmail, code, haslo)
                                // Haslo jest juz zmienione. Gdyby samo logowanie
                                // odbilo sie od limitera, nie wolno tego pokazac
                                // jako bledu zmiany hasla.
                                try {
                                    val u = Api.login(context, pendingEmail, haslo)
                                    if (u != null) {
                                        enterApp(u)
                                    } else {
                                        // Konto bez potwierdzonego adresu — serwer wyslal kod.
                                        error = null; cooldown = 60; route = Route.Verify
                                    }
                                } catch (e: Exception) {
                                    // E-mail zostaje wpisany na ekranie logowania, wiec wystarczy
                                    // nowe haslo. Przy limiterze mowimy wprost, ile odczekac —
                                    // wczesniej zapraszalismy do logowania prosto w ten sam limit.
                                    route = Route.Auth
                                    when {
                                        e is ApiError && e.code == "not_verified" -> {
                                            error = null; cooldown = 60; route = Route.Verify
                                        }
                                        e is ApiError && e.status == 429 -> error = tr("Hasło zmienione. ${e.message}", "Password changed. ${e.message}")
                                        else -> { error = null; toast = tr("Hasło zmienione. Zaloguj się nowym hasłem.", "Password changed. Sign in with the new password.") }
                                    }
                                }
                            } catch (e: Exception) {
                                // Odbicie od limitera nie uniewaznia kodu z maila.
                                error = if (e is ApiError && e.status == 429) {
                                    tr("${e.message} Twój kod jest dalej ważny.", "${e.message} Your code is still valid.")
                                } else {
                                    e.message
                                }
                                if (e is ApiError && e.code in setOf("code_invalid", "code_expired", "too_many")) {
                                    codeRejected++
                                }
                            } finally { busy = false }
                        }
                    },
                )

                Route.Settings -> Scaffold(
                    containerColor = Ink,
                    topBar = { SubHeader(tr("Ustawienia", "Settings")) { route = Route.Main } },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        SettingsScreen(
                            agentName = agentName,
                            onTerminal = { route = Route.Terminal },
                            onLogout = { logout() },
                        )
                    }
                }

                Route.Terminal -> Scaffold(
                    containerColor = Ink,
                    topBar = { SubHeader("Terminal") { route = Route.Settings } },
                ) { padding -> Box(Modifier.padding(padding)) { TerminalTab() } }

                Route.Main -> Scaffold(
                    containerColor = Ink,
                    topBar = {
                        TopBalance(
                            balance = user?.balanceUsd ?: 0.0,
                            bump = bump,
                            onSettings = { route = Route.Settings },
                        ) { tab = Tab.Shop }
                    },
                    bottomBar = { BottomBar(tab) { tab = it } },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                // Zakladka przesuwa sie w strone, w ktora idziesz po dolnym pasku.
                                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                (fadeIn(motionSpec(260)) + slideInHorizontally(motionSpec(320)) { dir * it / 10 })
                                    .togetherWith(fadeOut(motionSpec(160)) + slideOutHorizontally(motionSpec(320)) { -dir * it / 14 })
                            },
                            label = "tab",
                        ) { current ->
                            when (current) {
                                Tab.Chat -> ChatTab(
                                    messages = messages,
                                    agent = agentName,
                                    thinking = thinking,
                                    lastTokens = lastTokens,
                                    memory = memory,
                                    chatTitle = active.label,
                                    chatId = active.id,
                                    onOpenChats = { showChats = true },
                                    onPickAgent = { tab = Tab.Agents },
                                    staged = staged,
                                    importing = importing,
                                    onAttach = { uris -> Composer.add(context, uris) },
                                    onPaste = { Composer.pasteImage(context) },
                                    onRemoveAttachment = { Composer.remove(context, it) },
                                    images = images,
                                    drawing = active.id in drawingChats,
                                    onNote = { toast = it },
                                    sharedText = sharedText,
                                    onSharedTextUsed = { Composer.consumeText() },
                                    onSend = { text, _ ->
                                        val model = selectedAgent
                                        val uid = user?.id
                                        if (model == null) { toast = tr("Najpierw wybierz agenta.", "Pick an agent first."); return@ChatTab false }
                                        if (uid == null) return@ChatTab false
                                        // Odpowiedz trafia do rozmowy, w ktorej padlo pytanie —
                                        // nawet gdy przelaczysz rozmowe albo wyjdziesz z aplikacji.
                                        // W trakcie odpowiedzi wiadomosc czeka w kolejce i idzie sama po niej.
                                        // Zalaczniki opuszczaja pole dopiero, gdy wiadomosc naprawde poszla.
                                        val sent = ChatEngine.send(uid, active.id, model, text, Composer.staged.value)
                                        if (sent) Composer.take()
                                        // O gotowej odpowiedzi w tle mowi powiadomienie — pytamy o nie raz.
                                        scope.launch { NotificationPermission.askOnce(context) }
                                        sent
                                    },
                                    queued = queuedChats[active.id].orEmpty(),
                                    onStop = {
                                        user?.id?.let { uid ->
                                            // Czekajace wiadomosci nie ida same po przerwaniu — wracaja do pola.
                                            val back = ChatEngine.interrupt(uid, active.id)
                                            val dropped = if (back.isEmpty()) 0 else Composer.restore(
                                                context,
                                                back.map { it.text }.filter { it.isNotBlank() }.joinToString("\n"),
                                                back.flatMap { it.attachments },
                                            )
                                            toast = when {
                                                back.isEmpty() -> tr("Przerwano odpowiedź.", "Reply stopped.")
                                                dropped > 0 -> {
                                                    val without = count(dropped, "załącznika", "załączników", "załączników", "attachment", "attachments")
                                                    tr(
                                                        "Przerwano odpowiedź. Wiadomości wróciły do pola, ale bez $without — najwyżej ${Attachments.MAX_PER_MESSAGE} w jednej wiadomości.",
                                                        "Reply stopped. Your messages are back in the box, but without $without — at most ${Attachments.MAX_PER_MESSAGE} in one message.",
                                                    )
                                                }
                                                else -> tr(
                                                    "Przerwano odpowiedź. Wiadomości z kolejki wróciły do pola.",
                                                    "Reply stopped. Your queued messages are back in the box.",
                                                )
                                            }
                                        }
                                    },
                                    onUnqueue = { ChatEngine.unqueue(active.id, it) },
                                    onDelete = { index ->
                                        user?.id?.let { ChatEngine.deleteMessage(it, active.id, index) } == true
                                    },
                                )

                                Tab.Agents -> AgentsTab(agents, selectedAgent, busy, usage, lastTokens, onPick = {
                                    selectedAgent = it.id
                                    Prefs.setAgent(it.id)
                                    toast = tr("Wybrano: ${it.name}", "Selected: ${it.name}")
                                    tab = Tab.Chat
                                }) { scope.launch { loadAgents() } }

                                Tab.Shop -> ShopTab(
                                    shop = shop,
                                    error = shopError,
                                    holder = user?.name.orEmpty(),
                                    onRetry = {
                                        scope.launch {
                                            shopError = null
                                            shop = try { Api.shop() } catch (e: Exception) { shopError = e.message; null }
                                        }
                                    },
                                ) { pack -> buying = pack; bought = false }

                                Tab.Browser -> BrowserTab(
                                    holder = browser,
                                    agent = agentName,
                                    asking = asking,
                                    answer = answer,
                                    onCloseAnswer = { answer = null },
                                    onAsk = { pageText, url, question ->
                                        val model = selectedAgent
                                        if (model == null) { toast = tr("Najpierw wybierz agenta.", "Pick an agent first."); return@BrowserTab }
                                        scope.launch {
                                            asking = true
                                            try {
                                                val prompt = tr(
                                                    "Strona: $url\n\nTreść:\n$pageText\n\nPytanie: $question",
                                                    "Page: $url\n\nContent:\n$pageText\n\nQuestion: $question",
                                                )
                                                val r = Api.chat(model, listOf(ChatMessage(prompt, true)))
                                                answer = r.text
                                                ChatEngine.noteUsage(r.inTokens to r.outTokens)
                                            } catch (e: Exception) { toast = e.message }
                                            finally { asking = false }
                                        }
                                    },
                                )

                                Tab.Account -> AccountTab(
                                    user = user,
                                    usage = usage,
                                    plan = plan,
                                    busy = busy,
                                    accounts = accounts,
                                    // Token drugiego konta lezy w szyfrowanym schowku,
                                    // wiec przed wejsciem na nie prosimy o drugi skladnik.
                                    onSwitch = { acc -> switchTo(acc) },
                                    onAddAccount = {
                                        // Nowe logowanie, ale bez kasowania juz zapamietanych kont.
                                        // Inne konto = inny adres, wiec pole zaczyna puste.
                                        pendingEmail = ""
                                        Api.saveToken(context, null)
                                        user = null; usage = null; shop = null; plan = null
                                        agents = emptyList()
                                        route = Route.Auth
                                    },
                                    onSettings = { route = Route.Settings },
                                    onTopUp = { tab = Tab.Shop },
                                ) { logout() }
                            }
                        }
                    }
                }
            }
        }

        // Zgoda na polecenie zlecone przez model. Ten sam dialog co przy poleceniach
        // wpisywanych recznie — model nie ma drogi na skroty.
        // Tylko dla zalogowanej osoby: na ekranie logowania prosba czeka.
        approval?.takeIf { user != null }?.let { request ->
            // Klucz: kazda prosba to osobne okno, nawet gdy model poprosi dwa razy o to samo.
            key(request) {
                PermissionDialog(
                    title = tr("Model prosi o wykonanie polecenia", "The model asks to run a command"),
                    what = request.command,
                    detail = tr(
                        "Rozmowa „${request.chat}”. Wynik wróci do modelu i policzy się jako tokeny.",
                        "Chat \"${request.chat}\". The result goes back to the model and counts as tokens.",
                    ),
                    allowAlways = false,
                    onAllowOnce = { ChatEngine.answer(request, true) },
                    onAllowAlways = {},
                    onDeny = { ChatEngine.answer(request, false) },
                )
            }
        }

        if (showChats) {
            ChatListSheet(
                chats = chats,
                activeId = activeId,
                onPick = { showChats = false; ChatEngine.select(it) },
                onNew = {
                    user?.id?.let { ChatEngine.newChat(it) }
                    showChats = false
                },
                onRename = { id, name -> user?.id?.let { ChatEngine.rename(it, id, name) } },
                onDelete = { id -> user?.id?.let { ChatEngine.deleteChat(it, id) } },
                onDismiss = { showChats = false },
            )
        }

        // Termux, ktory nie moze wspolpracowac z CYPHR (np. z Google Play): propozycja wymiany
        // przy wejsciu do aplikacji — raz dla danej wersji. „Wymien teraz” otwiera ekran
        // Termuksa z dwoma krokami: odinstalowanie starego i instalacja wlasciwego.
        termuxOld?.let { version ->
            AlertDialog(
                onDismissRequest = { Prefs.setTermuxPrompted(version); termuxOld = null },
                containerColor = Raise,
                title = { Text(tr("Termux do wymiany", "Termux needs replacing"), color = Paper, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        if (isEn) {
                            (if (Termux.isPlayBuild(version)) "You have Termux from Google Play — this version doesn't accept commands from other apps"
                            else "Your Termux is too old or doesn't accept commands from other apps") +
                                ", so CYPHR can't use it. CYPHR will replace it with Termux from F-Droid: " +
                                "you uninstall the current one, and the right one downloads and gets checked by itself."
                        } else {
                            (if (Termux.isPlayBuild(version)) "Masz Termuksa z Google Play — ta wersja nie przyjmuje poleceń od innych aplikacji"
                            else "Twój Termux jest za stary albo nie przyjmuje poleceń od innych aplikacji") +
                                ", więc CYPHR nie może z niego korzystać. CYPHR wymieni go na Termuksa z F-Droid: " +
                                "odinstalujesz obecny, a właściwy sam się pobierze i sprawdzi."
                        },
                        color = Mist,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { Prefs.setTermuxPrompted(version); termuxOld = null; termuxSheet = true }) {
                        Text(tr("Wymień teraz", "Replace now"), color = Paper, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { Prefs.setTermuxPrompted(version); termuxOld = null }) {
                        Text(tr("Nie teraz", "Not now"), color = Mist)
                    }
                },
            )
        }
        if (termuxSheet) TermuxSheet(onDismiss = { termuxSheet = false })

        // Zakup: karta 3D i potwierdzenie
        buying?.let { pack ->
            BuyOverlay(
                pack = pack,
                holder = user?.name.orEmpty(),
                done = bought,
                busy = busy,
                onClose = { buying = null; bought = false },
                onConfirm = {
                    val pay = {
                        scope.launch {
                        busy = true
                        try {
                            val (fresh, credited) = Api.buy(pack.id)
                            user = fresh
                            bump += 1
                            bought = true
                            delay(1400)
                            buying = null
                            bought = false
                            toast = if (credited > 0) tr("Doładowano ${usd(credited)}.", "Topped up ${usd(credited)}.")
                            else tr("Zakup zasymulowany. Saldo bez zmian.", "Purchase simulated. Balance unchanged.")
                            shop = try { Api.shop() } catch (e: Exception) { shop }
                        } catch (e: Exception) { toast = e.message } finally { busy = false }
                        }
                        Unit
                    }
                    if (Prefs.confirmBuy) requireFingerprint(tr("Potwierdź zakup", "Confirm purchase")) { pay() } else pay()
                },
            )
        }

        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp),
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(18.dp)).background(Paper)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) { Text(toast ?: "", color = Ink) }
        }
    }
}

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(50))
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_back), tr("Wróć", "Back"), tint = Paper, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(title, color = Paper, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun BuyOverlay(
    pack: Pack,
    holder: String,
    done: Boolean,
    busy: Boolean,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.92f))
            .clickable(enabled = !busy && !done) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BuyAnimation(
                label = tr("Pakiet ${pack.name}", "${pack.name} pack"),
                amount = usd(pack.amountUsd),
                holder = holder,
                done = done,
            )
            Spacer(Modifier.height(28.dp))
            if (!done) {
                Lead(tr("Płatność testowa. Nic nie zostanie pobrane.", "Test payment. Nothing will be charged."), center = true)
                Spacer(Modifier.height(16.dp))
                PrimaryButton(tr("Zapłać ${usd(pack.amountUsd)}", "Pay ${usd(pack.amountUsd)}"), busy = busy, onClick = onConfirm)
                Spacer(Modifier.height(10.dp))
                GhostButton(tr("Anuluj", "Cancel"), enabled = !busy, onClick = onClose)
            } else {
                Text(tr("Gotowe", "Done"), color = Paper, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

/**
 * Odcisk SHA-1 certyfikatu, ktorym podpisano te paczke. To jego Google porownuje
 * z wpisem w konsoli — wiec pokazujemy wartosc odczytana z zainstalowanej
 * aplikacji, a nie z dokumentacji czy z pamieci.
 */
private fun podpisSha1(context: Context): String = try {
    val pm = context.packageManager
    val podpisy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
    }
    val cert = podpisy?.firstOrNull() ?: error("no signature")
    java.security.MessageDigest.getInstance("SHA-1")
        .digest(cert.toByteArray())
        .joinToString(":") { "%02X".format(it) }
} catch (e: Exception) {
    tr("nie udało się odczytać", "couldn't read it")
}

private fun validate(register: Boolean, email: String, password: String): String? = when {
    !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email) -> tr("Podaj poprawny adres e-mail.", "Enter a valid email address.")
    password.isBlank() -> tr("Wpisz hasło.", "Enter your password.")
    register && password.length < 8 -> tr("Hasło musi mieć co najmniej 8 znaków.", "The password must be at least 8 characters.")
    else -> null
}


