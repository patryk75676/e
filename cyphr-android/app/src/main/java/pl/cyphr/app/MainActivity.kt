package pl.cyphr.app

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

private enum class Route { Splash, Auth, Verify, Main, Terminal, Settings }

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

/**
 * Token przyniesiony z przegladarki po logowaniu Google na stronie.
 * Aktywnosc dziala w trybie singleTask, wiec powrot trafia do onNewIntent,
 * a nie tworzy nowej instancji.
 */
private val webGoogleToken = mutableStateOf<String?>(null)


class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SecureStore.init(this)
        Prefs.init(this)
        Api.load(this)
        catchGoogleReturn(intent)
        setContent { CyphrTheme { CyphrGate(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        catchGoogleReturn(intent)
    }

    private fun catchGoogleReturn(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "cyphr" && data.host == "google") {
            data.getQueryParameter("id_token")?.takeIf { it.isNotBlank() }?.let {
                webGoogleToken.value = it
            }
        }
    }
}

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

    fun ask(reason: String) {
        Biometrics.prompt(
            activity = activity,
            subtitle = reason,
            onSuccess = { unlocked = true; problem = null },
            onFailure = { problem = it },
        )
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> leftAt = System.currentTimeMillis()
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    // Stan moze sie zmienic, gdy ktos w miedzyczasie dopisal odcisk w ustawieniach
                    bio = Biometrics.state(activity)
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
        CyphrApp(
            requireFingerprint = { reason, onOk ->
                if (Biometrics.state(activity) !is Biometrics.State.Ready) onOk()
                else Biometrics.prompt(activity, reason, onSuccess = onOk)
            },
        )
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

@Composable
private fun CyphrApp(requireFingerprint: (String, () -> Unit) -> Unit = { _, ok -> ok() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var route by remember { mutableStateOf(Route.Splash) }
    SecureWindow(route == Route.Auth || route == Route.Verify || Prefs.secureScreen)

    var user by remember { mutableStateOf<User?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingEmail by remember { mutableStateOf("") }
    var cooldown by remember { mutableStateOf(0) }
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

    /** Podmienia aktywna rozmowe i zapisuje ja pod kontem zalogowanego uzytkownika. */
    fun updateActive(block: (Chat) -> Chat) {
        val next = block(active).copy(updatedAt = System.currentTimeMillis())
        chats = chats.map { if (it.id == next.id) next else it }
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

    // Przegladarka
    var answer by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }

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

    /** Nowe logowanie przechodzi przez drugi składnik. Wznowienie sesji nie, bo brama już pytała. */
    fun enterApp(u: User) {
        requireFingerprint("Potwierdź logowanie") { accounts = Accounts.all(); open(u) }
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
        // Pierwszy na liscie jest najmocniejszy, a przy tym najdrozszy — nikogo na
        // niego nie wrzucamy bez jego wiedzy.
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
                    CommonStatusCodes.DEVELOPER_ERROR ->
                        "Kod 10: Google nie rozpoznaje tej wersji aplikacji.\n" +
                            "Klient OAuth Android istnieje, ale jeszcze nie działa — " +
                            "Google propaguje nowe wpisy od 5 minut do kilku godzin.\n" +
                            "Sprawdź też, czy w konsoli zgadza się pl.cyphr.app i odcisk SHA-1."
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

    // Powrot ze strony logowania Google. Token jest jednorazowy, wiec od razu go zerujemy,
    // zeby ponowne wejscie do aplikacji nie probowalo logowac drugi raz.
    LaunchedEffect(webGoogleToken.value) {
        val idToken = webGoogleToken.value ?: return@LaunchedEffect
        webGoogleToken.value = null
        busy = true
        error = null
        try {
            Api.google(context, idToken)?.let { enterApp(it) }
        } catch (e: Exception) {
            error = e.message
            route = Route.Auth
        } finally {
            busy = false
        }
    }

    LaunchedEffect(cooldown) { if (cooldown > 0) { delay(1000); cooldown -= 1 } }
    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }

    LaunchedEffect(route, tab) {
        if (route != Route.Main) return@LaunchedEffect
        when (tab) {
            Tab.Agents -> loadAgents()
            Tab.Chat -> if (agents.isEmpty()) loadAgents()
            Tab.Shop -> shop = try { Api.shop() } catch (e: Exception) { toast = e.message; null }
            Tab.Account -> {
                usage = try { Api.usage() } catch (e: Exception) { null }
                usage?.let { u -> user = user?.copy(balanceUsd = u.balanceUsd) }
                try {
                    val profile = Api.profile()
                    plan = profile.plan
                    user = profile.user
                } catch (e: Exception) { plan = null }
            }
            Tab.Browser -> Unit
        }
    }

    BackHandler(enabled = route == Route.Terminal || route == Route.Settings || (route == Route.Main && tab != Tab.Chat)) {
        when {
            route == Route.Terminal -> route = Route.Settings
            route == Route.Settings -> route = Route.Main
            else -> tab = Tab.Chat
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                (fadeIn(motionSpec(320)) + slideInVertically(motionSpec(420)) { it / 14 })
                    .togetherWith(fadeOut(motionSpec(220)))
            },
            label = "route",
        ) { current ->
            when (current) {
                Route.Splash -> SplashScreen()

                Route.Auth -> AuthScreen(
                    busy = busy,
                    error = error,
                    onGoogle = {
                        error = null
                        // Tylko natywny wybor konta — zadnej przegladarki. signOut()
                        // przed startem wymusza liste kont zamiast cichego wejscia
                        // na ostatnio uzyte.
                        googleClient.signOut().addOnCompleteListener {
                            googleLauncher.launch(googleClient.signInIntent)
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
                            catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    },
                )

                Route.Settings -> Scaffold(
                    containerColor = Ink,
                    topBar = { SubHeader("Ustawienia") { route = Route.Main } },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        SettingsScreen(
                            agentName = agents.firstOrNull { it.id == selectedAgent }?.name ?: selectedAgent,
                            onTerminal = { route = Route.Terminal },
                            onClosed = { toast = "Zapisano adres API." },
                            onLogout = {
                                scope.launch {
                                    val gone = user?.id
                                    Api.logout(context)
                                    googleClient.signOut()
                                    gone?.let { Accounts.remove(it) }
                                    accounts = Accounts.all()
                                    usage = null; shop = null; plan = null
                                    agents = emptyList()
                                    // Rozmowy zostaja na dysku pod kontem — wroca po zalogowaniu.
                                    val next = accounts.firstOrNull()
                                    if (next == null) {
                                        user = null
                                        route = Route.Auth
                                    } else {
                                        Api.useAccount(context, next)
                                        user = Api.cachedUser()
                                        user = try { Api.me() } catch (e: Exception) { user }
                                        route = Route.Main
                                    }
                                }
                            },
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
                                (fadeIn(motionSpec(260)) + slideInVertically(motionSpec(320)) { it / 18 })
                                    .togetherWith(fadeOut(motionSpec(180)))
                            },
                            label = "tab",
                        ) { current ->
                            when (current) {
                                Tab.Chat -> ChatTab(
                                    messages = messages,
                                    // Klientowi pokazujemy nazwe, nie identyfikator techniczny.
                                    agent = agents.firstOrNull { it.id == selectedAgent }?.name ?: selectedAgent,
                                    thinking = thinking,
                                    lastTokens = lastTokens,
                                    memory = memory,
                                    chatTitle = active.label,
                                    onOpenChats = { showChats = true },
                                    onPickAgent = { tab = Tab.Agents },
                                    onSend = { text ->
                                        val model = selectedAgent
                                        if (model == null) { toast = "Najpierw wybierz agenta."; return@ChatTab }
                                        val sent = messages + ChatMessage(text, true)
                                        updateActive { it.copy(messages = sent) }
                                        scope.launch {
                                            thinking = true
                                            try {
                                                // Starsze wymiany zwijamy w notatke, zanim polecą po raz kolejny.
                                                var mem = memory
                                                if (shouldFold(sent, mem)) {
                                                    mem = Api.fold(model, sent, mem)
                                                    updateActive { it.copy(memory = mem) }
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
                                                    updateActive { it.copy(messages = history) }
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
                                                    updateActive { it.copy(messages = history) }
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
                                                updateActive {
                                                    it.copy(messages = it.messages + ChatMessage(shown, false))
                                                }
                                                lastTokens = reply.inTokens to reply.outTokens
                                                // Odswiezamy saldo po kazdej wiadomosci
                                                try {
                                                    val u = Api.me()
                                                    user = u
                                                } catch (_: Exception) {}
                                            } catch (e: Exception) {
                                                toast = e.message
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

                                Tab.Shop -> ShopTab(shop, user?.name.orEmpty()) { pack -> buying = pack; bought = false }

                                Tab.Browser -> BrowserTab(
                                    agent = selectedAgent,
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
                                    onSwitch = { acc ->
                                        // Token drugiego konta lezy w szyfrowanym schowku,
                                        // wiec przed wejsciem na nie prosimy o odcisk.
                                        requireFingerprint("Przełącz na ${acc.email}") {
                                            scope.launch {
                                                Api.useAccount(context, acc)
                                                user = Api.cachedUser()
                                                usage = null; shop = null; plan = null
                                                agents = emptyList(); lastTokens = null
                                                user = try { Api.me() } catch (e: Exception) { user }
                                                accounts = Accounts.all()
                                                tab = Tab.Chat
                                            }
                                        }
                                    },
                                    onAddAccount = {
                                        // Nowe logowanie, ale bez kasowania juz zapamietanych kont.
                                        Api.saveToken(context, null)
                                        user = null; usage = null; shop = null; plan = null
                                        agents = emptyList()
                                        route = Route.Auth
                                    },
                                    onSettings = { route = Route.Settings },
                                    onTopUp = { tab = Tab.Shop },
                                ) {
                                    scope.launch {
                                        busy = true
                                        val gone = user?.id
                                        Api.logout(context)
                                        googleClient.signOut()
                                        gone?.let { Accounts.remove(it) }
                                        accounts = Accounts.all()
                                        busy = false
                                        usage = null; shop = null; plan = null
                                        agents = emptyList()
                                        // Rozmowy zostaja na dysku pod kontem — wroca po zalogowaniu.
                                        val next = accounts.firstOrNull()
                                        if (next == null) {
                                            user = null
                                            route = Route.Auth
                                        } else {
                                            Api.useAccount(context, next)
                                            user = Api.cachedUser()
                                            user = try { Api.me() } catch (e: Exception) { user }
                                            tab = Tab.Chat
                                        }
                                    }
                                }
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

private fun validate(register: Boolean, email: String, password: String): String? = when {
    !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email) -> "Podaj poprawny adres e-mail."
    password.isBlank() -> "Wpisz hasło."
    register && password.length < 8 -> "Hasło musi mieć co najmniej 8 znaków."
    else -> null
}


