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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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
        enableEdgeToEdge()
        SecureStore.init(this)
        Prefs.init(this)
        Api.load(this)
        // Logowanie Google idzie wylacznie natywnym wyborem konta. Dawny powrot
        // z przegladarki (cyphr://google?id_token=...) przyjmowal token od dowolnej
        // strony albo aplikacji — mogla zalogowac telefon na cudze konto. Usuniety.
        setContent { CyphrTheme { CyphrGate(this) } }
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
    var unlocked by remember { mutableStateOf(Api.token() == null || !hasLock) }
    var problem by remember { mutableStateOf<String?>(null) }
    var leftAt by remember { mutableStateOf(0L) }

    // Logowanie czekajace, az telefon bedzie mial czym potwierdzic tozsamosc.
    var pending by remember { mutableStateOf<SecondFactor?>(null) }

    fun ask(reason: String) {
        Biometrics.prompt(
            activity = activity,
            subtitle = reason,
            onSuccess = { unlocked = true; problem = null },
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
            Biometrics.prompt(activity, request.reason, onSuccess = request.onOk, onFailure = request.onFail)
        } else {
            pending = request
        }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> leftAt = System.currentTimeMillis()
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    // Stan moze sie zmienic, gdy ktos w miedzyczasie dopisal odcisk w ustawieniach
                    bio = Biometrics.state(activity)
                    // Powrot z ustawien z gotowa blokada: czekajace logowanie rusza dalej.
                    pending?.let { if (bio is Biometrics.State.Ready) confirm(it) }
                    val away = (System.currentTimeMillis() - leftAt) / 1000
                    if (bio is Biometrics.State.Ready && Prefs.appLock && leftAt > 0L &&
                        away >= Prefs.lockAfterSeconds && Api.token() != null
                    ) {
                        unlocked = false
                    }
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(unlocked) { if (!unlocked) ask("Potwierdź, że to Ty") }

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
            Text("CYPHR jest zablokowany", color = Paper, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Lead(
                problem ?: ready?.let { "Drugi składnik logowania: ${it.label}." }
                    ?: "Ustaw blokadę ekranu, żeby otworzyć konto.",
                center = true,
            )
            Spacer(Modifier.height(22.dp))
            Box(Modifier.width(230.dp)) {
                if (bio is Biometrics.State.NotEnrolled) {
                    PrimaryButton("Ustaw blokadę") { activity.startActivity(Biometrics.enrollIntent()) }
                } else {
                    PrimaryButton("Odblokuj") { ask("Potwierdź, że to Ty") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(230.dp)) {
                GhostButton("Wyloguj się") {
                    Api.saveToken(activity, null)
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
        Text("Potrzebna blokada ekranu", color = Paper, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        Lead(
            when (state) {
                Biometrics.State.None ->
                    "To urządzenie nie potrafi potwierdzić tożsamości, a bez tego CYPHR nie otworzy konta."
                Biometrics.State.Unavailable ->
                    "Czytnik jest chwilowo niedostępny. Spróbuj za moment."
                else ->
                    "Po zalogowaniu CYPHR prosi o drugi składnik — odcisk palca, twarz albo kod ekranu. " +
                        "Ten telefon nie ma jeszcze ustawionej blokady. Ustaw ją i wróć, logowanie ruszy samo."
            },
            center = true,
        )
        Spacer(Modifier.height(22.dp))
        Box(Modifier.width(250.dp)) {
            if (state is Biometrics.State.NotEnrolled) PrimaryButton("Ustaw blokadę", onClick = onSetUp)
            else PrimaryButton("Spróbuj ponownie", onClick = onRetry)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(250.dp)) { GhostButton("Anuluj logowanie", onClick = onCancel) }
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

    // Czat
    var chats by remember { mutableStateOf(listOf(Chat())) }
    var activeId by remember { mutableStateOf(chats.first().id) }
    var showChats by remember { mutableStateOf(false) }

    // Rozmowy naleza do konta, nie do instalacji. Wczytujemy je, gdy wiadomo kto
    // jest zalogowany, i zostawiamy na dysku przy wylogowaniu.
    LaunchedEffect(user?.id) {
        val uid = user?.id
        chats = if (uid == null) listOf(Chat())
        else withContext(Dispatchers.IO) { Chats.loadAll(context, uid) }.ifEmpty { listOf(Chat()) }
        activeId = chats.first().id
    }

    val active = chats.firstOrNull { it.id == activeId } ?: chats.first()
    val messages = active.messages
    val memory = active.memory
    // Na ekranie zawsze nazwa CYPHR — nigdy identyfikator, ktory idzie do serwera.
    val agentName = selectedAgent?.let { Persona.nameOf(it) }

    /**
     * Podmienia rozmowe o danym id i zapisuje ja pod kontem zalogowanego uzytkownika.
     * Liczy od aktualnego stanu listy (`chats` czyta stan, nie kopie), bo ta funkcja
     * jest wolana z korutyny kilka sekund po wyslaniu — kopia `active` z tamtej chwili
     * nie zawierala jeszcze pytania i odpowiedz je nadpisywala.
     */
    fun updateChat(id: String, block: (Chat) -> Chat) {
        val (list, next) = chats.updated(id, System.currentTimeMillis(), block) ?: return
        chats = list
        val uid = user?.id ?: return
        scope.launch { withContext(Dispatchers.IO) { Chats.save(context, uid, next) } }
    }
    var thinking by remember { mutableStateOf(false) }
    /** Rzeczywiste zuzycie ostatniej wymiany: wejscie do outputu. */
    var lastTokens by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Polecenie, o ktore poprosil model, wraz z odpowiedzia czekajaca na uzytkownika.
    var pendingCommand by remember { mutableStateOf<Pair<String, (Boolean) -> Unit>?>(null) }

    suspend fun askCommand(command: String): Boolean = suspendCancellableCoroutine { cont ->
        pendingCommand = command to { allowed ->
            pendingCommand = null
            if (cont.isActive) cont.resume(allowed)
        }
        cont.invokeOnCancellation { pendingCommand = null }
    }

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
            "Potwierdź logowanie",
            { accounts = Accounts.all(); open(u) },
            { reason ->
                abandonLogin(u)
                route = Route.Auth
                error = reason ?: "Logowanie przerwane — bez potwierdzenia tożsamości konto się nie otworzy."
            },
        )
    }

    /** Wejscie na zapamietane konto to tez logowanie — z drugim skladnikiem. */
    fun switchTo(acc: Account, onRefused: () -> Unit = {}) {
        requireSecondFactor(
            "Wejdź na ${acc.email}",
            {
                scope.launch {
                    Api.useAccount(context, acc)
                    user = Api.cachedUser()
                    usage = null; shop = null; plan = null
                    agents = emptyList(); lastTokens = null
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
            Api.logout(context)
            googleClient.signOut()
            gone?.let { Accounts.remove(it) }
            accounts = Accounts.all()
            usage = null; shop = null; plan = null
            agents = emptyList(); lastTokens = null
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
                val idToken = account.idToken ?: throw ApiError("Google nie zwrócił tokenu.")
                Api.google(context, idToken)?.let { enterApp(it) }
            } catch (e: ApiException) {
                error = when (e.statusCode) {
                    GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> null
                    // Zamiast ogolnej porady pokazujemy wartosci odczytane z tej
                    // konkretnej paczki — to je Google porownuje z konsola, wiec
                    // da sie je zestawic pole po polu bez zgadywania.
                    CommonStatusCodes.DEVELOPER_ERROR ->
                        "Kod 10: Google nie rozpoznaje tej wersji aplikacji.\n\n" +
                            "W Google Cloud Console klient OAuth typu Android musi być " +
                            "w tym samym projekcie co klient Web i mieć dokładnie:\n\n" +
                            "pakiet: ${context.packageName}\n" +
                            "SHA-1: ${podpisSha1(context)}\n" +
                            "projekt: ${BuildConfig.GOOGLE_WEB_CLIENT_ID.substringBefore('-')}\n\n" +
                            "Jeśli wszystko się zgadza, Google propaguje nowe wpisy " +
                            "od 5 minut do kilku godzin."
                    CommonStatusCodes.NETWORK_ERROR -> "Brak połączenia z Google."
                    GoogleSignInStatusCodes.SIGN_IN_FAILED ->
                        "Google odrzucił logowanie. Upewnij się, że ekran zgody OAuth " +
                            "jest skonfigurowany w tym samym projekcie."
                    else -> "Logowanie Google nie powiodło się (kod ${e.statusCode})."
                }
            } catch (e: Exception) {
                error = e.message
            } finally { busy = false }
        }
    }

    LaunchedEffect(Unit) {
        val started = System.currentTimeMillis()
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
                            "Wpisz swój adres e-mail, wyślemy na niego kod."
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
                                    else "${e.javaClass.simpleName}: ${e.message ?: "brak szczegółów"}"
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
                            try { Api.resend(pendingEmail); cooldown = 60; toast = "Wysłaliśmy nowy kod." }
                            catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    },
                    onSubmit = { code ->
                        if (code.length != 6) error = "Wpisz wszystkie 6 cyfr."
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
                            try { Api.forgot(pendingEmail); cooldown = 60; toast = "Wysłaliśmy nowy kod." }
                            catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    },
                    onSubmit = { code, haslo ->
                        error = when {
                            code.length != 6 -> "Wpisz wszystkie 6 cyfr."
                            haslo.length < 8 -> "Hasło musi mieć co najmniej 8 znaków."
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
                                        e is ApiError && e.status == 429 -> error = "Hasło zmienione. ${e.message}"
                                        else -> { error = null; toast = "Hasło zmienione. Zaloguj się nowym hasłem." }
                                    }
                                }
                            } catch (e: Exception) {
                                // Odbicie od limitera nie uniewaznia kodu z maila.
                                error = if (e is ApiError && e.status == 429) {
                                    "${e.message} Twój kod jest dalej ważny."
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
                    topBar = { SubHeader("Ustawienia") { route = Route.Main } },
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
                                    onSend = { text ->
                                        val model = selectedAgent
                                        if (model == null) { toast = "Najpierw wybierz agenta."; return@ChatTab }
                                        // Odpowiedz trafia do rozmowy, w ktorej padlo pytanie —
                                        // nawet gdy w trakcie przelaczysz sie na inna.
                                        val chatId = active.id
                                        val sent = messages + ChatMessage(text, true)
                                        updateChat(chatId) { it.copy(messages = sent) }
                                        scope.launch {
                                            thinking = true
                                            try {
                                                // Starsze wymiany zwijamy w notatke, zanim polecą po raz kolejny.
                                                var mem = memory
                                                if (shouldFold(sent, mem)) {
                                                    mem = Api.fold(model, sent, mem)
                                                    updateChat(chatId) { it.copy(memory = mem) }
                                                }
                                                val tools = if (Prefs.agentTerminal) {
                                                    AgentTools.instructions(AgentTools.target(context))
                                                } else {
                                                    null
                                                }
                                                var history = sent
                                                var reply = Api.chat(model, history, mem, tools)
                                                var round = 0

                                                // Model moze poprosic o polecenie. Kazde przechodzi przez
                                                // zgode uzytkownika, a petla ma twardy limit, zeby nie
                                                // zapetlic sie na saldzie.
                                                while (Prefs.agentTerminal && round < AgentTools.MAX_ROUNDS) {
                                                    val cmd = AgentTools.requestedCommand(reply.text) ?: break
                                                    // Tura modelu zostaje w historii razem z poleceniem —
                                                    // uzytkownik widzi, co idzie do terminala, a model wie,
                                                    // o co sam poprosil.
                                                    val spoken = AgentTools.withoutCall(reply.text)
                                                    history = history + ChatMessage(
                                                        listOf(spoken, "$ $cmd").filter { it.isNotBlank() }
                                                            .joinToString("\n\n"),
                                                        false,
                                                    )
                                                    updateChat(chatId) { it.copy(messages = history) }
                                                    val allowed = askCommand(cmd)
                                                    val result = if (allowed) {
                                                        AgentTools.execute(context, cmd)
                                                    } else {
                                                        "Użytkownik odmówił wykonania tego polecenia."
                                                    }
                                                    history = history + ChatMessage(
                                                        "Wynik polecenia `$cmd`:\n$result",
                                                        true,
                                                    )
                                                    updateChat(chatId) { it.copy(messages = history) }
                                                    reply = Api.chat(model, history, mem, tools)
                                                    lastTokens = reply.inTokens to reply.outTokens
                                                    round++
                                                }

                                                var shown = AgentTools.withoutCall(reply.text).ifBlank { reply.text }
                                                // Limit wyczerpany, a model wciaz prosi o kolejne polecenie.
                                                if (Prefs.agentTerminal && round >= AgentTools.MAX_ROUNDS &&
                                                    AgentTools.requestedCommand(reply.text) != null
                                                ) {
                                                    shown += "\n\n(Przerwano po ${AgentTools.MAX_ROUNDS} poleceniach. " +
                                                        "Napisz „kontynuuj”, żeby pracował dalej.)"
                                                }
                                                updateChat(chatId) {
                                                    it.copy(messages = it.messages + ChatMessage(shown, false))
                                                }
                                                lastTokens = reply.inTokens to reply.outTokens
                                                // Odswiezamy saldo po kazdej wiadomosci
                                                try {
                                                    val u = Api.me()
                                                    user = u
                                                } catch (_: Exception) {}
                                            } catch (e: Exception) {
                                                // Chwilowa awaria modelu nie jest wina aplikacji ani salda —
                                                // drugi model zwykle dziala. Wlasny tekst, bo serwerowy
                                                // mowi o tym, co stoi za modelem.
                                                toast = if (e is ApiError && e.code == "upstream_error") {
                                                    "${Persona.nameOf(model)} chwilowo nie odpowiada. " +
                                                        "Spróbuj ponownie albo wybierz drugi model w zakładce Agenci."
                                                } else {
                                                    e.message
                                                }
                                            } finally { thinking = false }
                                        }
                                    },
                                )

                                Tab.Agents -> AgentsTab(agents, selectedAgent, busy, usage, lastTokens, onPick = {
                                    selectedAgent = it.id
                                    Prefs.setAgent(it.id)
                                    toast = "Wybrano: ${it.name}"
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
                                        if (model == null) { toast = "Najpierw wybierz agenta."; return@BrowserTab }
                                        scope.launch {
                                            asking = true
                                            try {
                                                val prompt = "Strona: $url\n\nTreść:\n$pageText\n\nPytanie: $question"
                                                val r = Api.chat(model, listOf(ChatMessage(prompt, true)))
                                                answer = r.text
                                                lastTokens = r.inTokens to r.outTokens
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
        pendingCommand?.let { (command, answer) ->
            PermissionDialog(
                title = "Model prosi o wykonanie polecenia",
                what = command,
                detail = "Wynik wróci do modelu i policzy się jako tokeny.",
                allowAlways = false,
                onAllowOnce = { answer(true) },
                onAllowAlways = {},
                onDeny = { answer(false) },
            )
        }

        if (showChats) {
            ChatListSheet(
                chats = chats,
                activeId = activeId,
                onPick = { showChats = false; activeId = it },
                onNew = {
                    // Pusta rozmowa nie ma sensu mnozyc — jesli biezaca jest pusta, zostajemy w niej.
                    if (active.messages.isEmpty()) { showChats = false } else {
                        val fresh = Chat()
                        chats = listOf(fresh) + chats
                        activeId = fresh.id
                        showChats = false
                        user?.id?.let { uid ->
                            scope.launch { withContext(Dispatchers.IO) { Chats.save(context, uid, fresh) } }
                        }
                    }
                },
                onRename = { id, name ->
                    chats = chats.map { c ->
                        if (c.id == id) c.copy(title = name).also { renamed ->
                            user?.id?.let { uid ->
                                scope.launch { withContext(Dispatchers.IO) { Chats.save(context, uid, renamed) } }
                            }
                        } else c
                    }
                },
                onDelete = { id ->
                    user?.id?.let { uid ->
                        scope.launch { withContext(Dispatchers.IO) { Chats.delete(context, uid, id) } }
                    }
                    val left = chats.filterNot { it.id == id }
                    // Zawsze zostaje przynajmniej jedna rozmowa, zeby ekran czatu mial co pokazac.
                    chats = left.ifEmpty { listOf(Chat()) }
                    if (activeId == id) activeId = chats.first().id
                },
                onDismiss = { showChats = false },
            )
        }

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
                            toast = if (credited > 0) "Doładowano ${usd(credited)}." else "Zakup zasymulowany. Saldo bez zmian."
                            shop = try { Api.shop() } catch (e: Exception) { shop }
                        } catch (e: Exception) { toast = e.message } finally { busy = false }
                        }
                        Unit
                    }
                    if (Prefs.confirmBuy) requireFingerprint("Potwierdź zakup") { pay() } else pay()
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
            Icon(painterResource(R.drawable.ic_back), "Wróć", tint = Paper, modifier = Modifier.size(20.dp))
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
                label = "Pakiet ${pack.name}",
                amount = usd(pack.amountUsd),
                holder = holder,
                done = done,
            )
            Spacer(Modifier.height(28.dp))
            if (!done) {
                Lead("Płatność testowa. Nic nie zostanie pobrane.", center = true)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Zapłać ${usd(pack.amountUsd)}", busy = busy, onClick = onConfirm)
                Spacer(Modifier.height(10.dp))
                GhostButton("Anuluj", enabled = !busy, onClick = onClose)
            } else {
                Text("Gotowe", color = Paper, fontWeight = FontWeight.ExtraBold)
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
    val cert = podpisy?.firstOrNull() ?: error("brak podpisu")
    java.security.MessageDigest.getInstance("SHA-1")
        .digest(cert.toByteArray())
        .joinToString(":") { "%02X".format(it) }
} catch (e: Exception) {
    "nie udało się odczytać"
}

private fun validate(register: Boolean, email: String, password: String): String? = when {
    !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email) -> "Podaj poprawny adres e-mail."
    password.isBlank() -> "Wpisz hasło."
    register && password.length < 8 -> "Hasło musi mieć co najmniej 8 znaków."
    else -> null
}


