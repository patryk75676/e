package pl.cyphr.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val Pad = 22.dp

// ---------- Ekran startowy ----------
@Composable
fun SplashScreen() {
    val on = Prefs.animations
    val fade = remember { Animatable(if (on) 0f else 1f) }
    val scale = remember { Animatable(if (on) 0.74f else 1f) }
    val sweep = remember { Animatable(if (on) 0f else 1f) }
    val name = remember { Animatable(if (on) 0f else 1f) }

    LaunchedEffect(Unit) {
        if (!on) return@LaunchedEffect
        launch { fade.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
        launch { scale.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 240f)) }
        launch { delay(240); sweep.animateTo(1f, tween(640, easing = FastOutSlowInEasing)) }
        launch { delay(420); name.animateTo(1f, tween(560, easing = FastOutSlowInEasing)) }
    }

    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Ghost(
                size = 150.dp,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale.value; scaleY = scale.value; alpha = fade.value
                },
            )
            Spacer(Modifier.height(26.dp))
            Box(
                Modifier
                    .width(160.dp * sweep.value)
                    .height(1.5.dp)
                    .background(Line),
            )
            Spacer(Modifier.height(22.dp))
            Text(
                "CYPHR",
                color = Paper,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (16f - 10f * name.value).sp,
                modifier = Modifier.graphicsLayer { alpha = name.value },
            )
        }
    }
}

// ---------- Logowanie ----------
@Composable
fun AuthScreen(
    busy: Boolean,
    error: String?,
    onGoogle: () -> Unit,
    onForgot: (email: String) -> Unit,
    onSubmit: (register: Boolean, email: String, password: String, name: String) -> Unit,
    // Po resecie hasla albo powrocie z kodu adres jest juz znany — nie kazemy go przepisywac.
    initialEmail: String = "",
    // Konta zapamietane na telefonie. Bez tego „Zaloguj inne konto” bylo droga bez powrotu.
    saved: List<Account> = emptyList(),
    onUseSaved: (Account) -> Unit = {},
) {
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val enter = remember { Animatable(if (Prefs.animations) 0f else 1f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, motionSpec(600)) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Pad)
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Ghost(size = 120.dp, floating = true)
        Text(
            "CYPHR",
            fontSize = 52.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Paper,
            modifier = Modifier
                .padding(top = 16.dp, bottom = 22.dp)
                .graphicsLayer {
                    alpha = enter.value
                    translationY = (1f - enter.value) * 16f * density
                },
        )

        GoogleButton(onClick = onGoogle)
        Spacer(Modifier.height(16.dp))
        Divider(tr("albo e-mailem", "or with email"))
        Spacer(Modifier.height(16.dp))
        Segmented(register) { register = it }
        Spacer(Modifier.height(16.dp))

        if (register) {
            Field(name, tr("Imię", "Name"), { name = it })
            Spacer(Modifier.height(12.dp))
        }
        Field(email, tr("E-mail", "Email"), { email = it }, keyboard = KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        Field(password, tr("Hasło", "Password"), { password = it }, password = true)
        if (register) {
            Spacer(Modifier.height(6.dp))
            Text(tr("Co najmniej 8 znaków.", "At least 8 characters."), color = Mist, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        ErrorText(error)
        Spacer(Modifier.height(12.dp))
        GhostButton(if (register) tr("Załóż konto", "Create account") else tr("Zaloguj się", "Sign in"), busy = busy) {
            onSubmit(register, email.trim().lowercase(), password, name.trim())
        }

        // Przy zakladaniu konta nie ma czego odzyskiwac.
        if (!register) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { onForgot(email.trim().lowercase()) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr("Nie pamiętam hasła", "Forgot password"), color = Mist, fontSize = 14.sp)
            }
        }

        if (!register && saved.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Divider(tr("zapamiętane na telefonie", "saved on this phone"))
            Spacer(Modifier.height(12.dp))
            saved.forEach { acc ->
                GhostButton(acc.email, enabled = !busy) { onUseSaved(acc) }
                Spacer(Modifier.height(8.dp))
            }
            Lead(tr("Wejście na zapamiętane konto potwierdzasz odciskiem.", "You confirm a saved account with your fingerprint."), center = true)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Zmiana zapomnianego hasla: kod z maila i nowe haslo na jednym ekranie,
 * zeby nie przeklikiwac sie przez trzy pod rzad.
 */
@Composable
fun ResetScreen(
    email: String,
    busy: Boolean,
    error: String?,
    cooldown: Int,
    codeRejected: Int,
    onBack: () -> Unit,
    onResend: () -> Unit,
    onSubmit: (code: String, password: String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var haslo by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Pole kodu czyscimy tylko, gdy serwer odrzucil sam kod. Wczesniej znikal przy
    // kazdym bledzie — takze przy za krotkim hasle albo limiterze, choc kod byl dobry.
    LaunchedEffect(codeRejected) { if (codeRejected > 0) code = "" }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Pad),
    ) {
        Spacer(Modifier.height(8.dp))
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = tr("Wróć", "Back"), tint = Paper, modifier = Modifier.size(20.dp))
        }
        Ghost(size = 76.dp, floating = true, modifier = Modifier.padding(vertical = 12.dp))
        SectionTitle(tr("Nowe hasło", "New password"))
        Spacer(Modifier.height(8.dp))
        Lead(tr("Jeśli na $email jest konto, wysłaliśmy na nie 6-cyfrowy kod. Jest ważny 15 minut.", "If there is an account for $email, we've sent it a 6-digit code. It's valid for 15 minutes."))
        Spacer(Modifier.height(24.dp))

        BasicCodeField(code, focus) { code = it }
        Spacer(Modifier.height(14.dp))
        Field(haslo, tr("Nowe hasło", "New password"), { haslo = it }, password = true)
        Spacer(Modifier.height(6.dp))
        Text(tr("Co najmniej 8 znaków.", "At least 8 characters."), color = Mist, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(14.dp))
        ErrorText(error)
        Spacer(Modifier.height(14.dp))
        PrimaryButton(tr("Ustaw nowe hasło", "Set new password"), busy = busy) { onSubmit(code, haslo) }
        Spacer(Modifier.height(10.dp))
        GhostButton(
            if (cooldown > 0) tr("Wyślij ponownie za $cooldown s", "Resend in $cooldown s") else tr("Wyślij kod ponownie", "Resend code"),
            enabled = cooldown == 0 && !busy,
            onClick = onResend,
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun GoogleButton(onClick: () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, motionSpec(120), label = "googlePress")
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .background(Paper)
            .clickable(interactionSource = source, indication = null) { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_google),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(tr("Zaloguj się przez Google", "Sign in with Google"), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Kod z e-maila ----------
@Composable
fun VerifyScreen(
    email: String,
    busy: Boolean,
    error: String?,
    cooldown: Int,
    codeRejected: Int,
    onBack: () -> Unit,
    onResend: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Po odrzuconym kodzie pole samo sie czysci — inaczej trzeba kasowac szesc cyfr.
    // Ale nie przy limiterze czy braku sieci: wtedy kod jest dobry i wystarczy ponowic.
    LaunchedEffect(codeRejected) { if (codeRejected > 0) code = "" }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
            .padding(horizontal = Pad),
    ) {
        Spacer(Modifier.height(8.dp))
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = tr("Wróć", "Back"), tint = Paper, modifier = Modifier.size(20.dp))
        }
        Ghost(size = 76.dp, floating = true, modifier = Modifier.padding(vertical = 12.dp))
        SectionTitle(tr("Sprawdź skrzynkę", "Check your inbox"))
        Spacer(Modifier.height(8.dp))
        Lead(tr("Wysłaliśmy 6-cyfrowy kod na $email. Kod jest ważny 15 minut.", "We've sent a 6-digit code to $email. It's valid for 15 minutes."))
        Spacer(Modifier.height(24.dp))

        BasicCodeField(code, focus) { value ->
            code = value
            // Szosta cyfra wysyla kod sama, ale nie drugi raz w trakcie sprawdzania.
            if (value.length == 6 && !busy) onSubmit(value)
        }

        Spacer(Modifier.height(16.dp))
        ErrorText(error)
        Spacer(Modifier.height(16.dp))
        PrimaryButton(tr("Potwierdź", "Confirm"), busy = busy) { onSubmit(code) }
        Spacer(Modifier.height(10.dp))
        GhostButton(
            if (cooldown > 0) tr("Wyślij ponownie za $cooldown s", "Resend in $cooldown s") else tr("Wyślij kod ponownie", "Resend code"),
            enabled = cooldown == 0,
            onClick = onResend,
        )
    }
}

@Composable
private fun BasicCodeField(code: String, focus: FocusRequester, onChange: (String) -> Unit) {
    Box {
        TextField(
            value = code,
            onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(6)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .focusRequester(focus)
                .graphicsLayer { alpha = 0f },
        )
        Row(
            Modifier.fillMaxWidth().height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(6) { i ->
                val filled = i < code.length
                val cursor = i == code.length
                val edge by animateColorAsState(
                    if (cursor) Paper else if (filled) Mist else Line,
                    motionSpec(180),
                    label = "codeEdge$i",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(1.5.dp, edge, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        code.getOrNull(i)?.toString() ?: "",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Paper,
                    )
                }
            }
        }
    }
}

// ---------- Agenci ----------
/** Zuzycie z ostatnich 30 dni prosto z /me/usage, plus dokladne liczby ostatniej wymiany. */
@Composable
private fun AgentStats(usage: Usage?, lastTokens: Pair<Int, Int>?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Text(tr("ZUŻYCIE", "USAGE"), color = Mist, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatCell(tr("Tokeny / 30 dni", "Tokens / 30 days"), usage?.tokens?.let { int(it) } ?: "—")
            StatCell(tr("Wydane / 30 dni", "Spent / 30 days"), usage?.let { usd(it.spentUsd) } ?: "—")
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Line, thickness = 1.dp)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatCell(tr("Ostatnie wejście", "Last input"), lastTokens?.first?.let { int(it.toLong()) } ?: "—")
            StatCell(tr("Ostatnie wyjście", "Last output"), lastTokens?.second?.let { int(it.toLong()) } ?: "—")
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(label, color = Mist, fontSize = 12.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Paper, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun AgentsTab(
    agents: List<Agent>,
    selected: String?,
    busy: Boolean,
    usage: Usage?,
    lastTokens: Pair<Int, Int>?,
    onPick: (Agent) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Pad)) {
        SectionTitle(tr("Agenci", "Agents"), Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(6.dp))
        Lead(tr("Modele CYPHR. Wybrany odpowiada w czacie i w przeglądarce.", "CYPHR models. The one you pick answers in the chat and in the browser."))
        Spacer(Modifier.height(18.dp))

        if (agents.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Ghost(size = 96.dp, floating = true)
                Spacer(Modifier.height(20.dp))
                Text(tr("Brak agentów", "No agents"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Paper)
                Spacer(Modifier.height(6.dp))
                Lead(tr("Spróbuj odświeżyć listę.", "Try refreshing the list."), center = true)
                Spacer(Modifier.height(22.dp))
                GhostButton(tr("Odśwież", "Refresh"), Modifier.width(180.dp), busy = busy, onClick = onRefresh)
            }
        } else {
            // Kolejnosc z katalogu: domyslny model pierwszy.
            agents.forEach { agent ->
                AgentRow(agent = agent, selected = agent.id == selected, onPick = { onPick(agent) })
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        AgentStats(usage, lastTokens)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AgentRow(agent: Agent, selected: Boolean, onPick: () -> Unit) {
    val border by animateColorAsState(if (selected) Paper else Line, motionSpec(220), label = "agentBorder")
    val badge by animateColorAsState(if (selected) Paper else Raise, motionSpec(220), label = "agentBadge")
    val ghost by animateColorAsState(if (selected) Ink else Paper, motionSpec(220), label = "agentGhost")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.5.dp, border, RoundedCornerShape(20.dp))
            .clickable { onPick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(badge),
            contentAlignment = Alignment.Center,
        ) {
            Ghost(size = 28.dp, tint = ghost)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(agent.name, color = Paper, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            // Opis z katalogu w jezyku aplikacji — lista z serwera mogla przyjsc przed zmiana jezyka.
            val description = CATALOG.firstOrNull { it.id == agent.id }?.description ?: agent.description
            if (description.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(description, color = Mist, fontSize = 13.sp, lineHeight = 18.sp)
            }
            agent.price?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Mist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        val radioBg by animateColorAsState(if (selected) Paper else Color.Transparent, motionSpec(200), label = "radioBg")
        val check by animateFloatAsState(if (selected) 1f else 0f, motionSpring(0.5f, 600f), label = "check")
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(radioBg)
                .border(1.5.dp, border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_check),
                if (selected) tr("Wybrany", "Selected") else null,
                tint = Ink,
                modifier = Modifier.size(16.dp).graphicsLayer { scaleX = check; scaleY = check; alpha = check },
            )
        }
    }
}

// ---------- Sklep ----------
@Composable
fun ShopTab(shop: Shop?, error: String?, holder: String, onRetry: () -> Unit, onPick: (Pack) -> Unit) {
    var preview by remember(shop) { mutableStateOf(shop?.packages?.getOrNull(1)) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Pad),
    ) {
        Spacer(Modifier.height(6.dp))
        CreditCard3D(
            label = preview?.name?.let { tr("Pakiet $it", "$it pack") } ?: tr("Doładowanie", "Top-up"),
            amount = preview?.let { usd(it.amountUsd) } ?: "—",
            holder = holder,
        )
        Spacer(Modifier.height(20.dp))
        SectionTitle(tr("Doładuj saldo", "Top up balance"))
        Spacer(Modifier.height(8.dp))
        Lead(
            when {
                error != null -> tr("Nie udało się wczytać pakietów. $error", "Couldn't load the packs. $error")
                shop == null -> tr("Wczytuję pakiety.", "Loading packs.")
                shop.testLeftUsd > 0 -> tr(
                    "Tryb testowy. Nic nie płacisz. Zostało ${usd(shop.testLeftUsd)} testowych środków.",
                    "Test mode. You don't pay anything. ${usd(shop.testLeftUsd)} of test funds left.",
                )
                else -> tr(
                    "Tryb testowy. Limit testowych środków jest wyczerpany, więc zakupy są tylko symulowane.",
                    "Test mode. The test funds are used up, so purchases are only simulated.",
                )
            },
        )
        if (error != null) {
            Spacer(Modifier.height(14.dp))
            GhostButton(tr("Spróbuj ponownie", "Try again"), onClick = onRetry)
        }
        Spacer(Modifier.height(18.dp))
        shop?.packages?.forEach { pack ->
            val chosen = pack.id == preview?.id
            val bg by animateColorAsState(if (chosen) Paper else Ink, motionSpec(260), label = "packBg")
            val edge by animateColorAsState(if (chosen) Paper else Line, motionSpec(260), label = "packEdge")
            val label by animateColorAsState(if (chosen) Color(0xFF555555) else Mist, motionSpec(260), label = "packLabel")
            val amount by animateColorAsState(if (chosen) Ink else Paper, motionSpec(260), label = "packAmount")
            val pill by animateColorAsState(if (chosen) Ink else Paper, motionSpec(260), label = "packPill")
            val pillText by animateColorAsState(if (chosen) Paper else Ink, motionSpec(260), label = "packPillText")
            val lift by animateFloatAsState(if (chosen) 1f else 0.98f, motionSpring(0.6f, 380f), label = "packLift")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .graphicsLayer { scaleX = lift; scaleY = lift }
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .border(1.5.dp, edge, RoundedCornerShape(20.dp))
                    .clickable { if (chosen) onPick(pack) else preview = pack }
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(pack.name, color = label, fontSize = 15.sp)
                    Text(
                        usd(pack.amountUsd),
                        color = amount,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(pill)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    AnimatedContent(
                        targetState = chosen,
                        transitionSpec = { fadeIn(motionSpec(200)).togetherWith(fadeOut(motionSpec(120))) },
                        label = "packAction",
                    ) { on ->
                        Text(
                            if (on) tr("Kup", "Buy") else tr("Wybierz", "Choose"),
                            color = pillText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------- Konto ----------
/**
 * Konta zapamietane na tym telefonie. Odcisk palca odblokowuje aplikacje,
 * a stad wybiera sie, na ktorym koncie pracowac — przelaczenie nie pyta o haslo,
 * bo token juz lezy w szyfrowanym schowku.
 */
@Composable
private fun AccountSwitcher(
    accounts: List<Account>,
    activeId: Long?,
    onSwitch: (Account) -> Unit,
    onAdd: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val others = accounts.filterNot { it.id == activeId }

    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.5.dp, Line, RoundedCornerShape(16.dp))
                .clickable { open = !open }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (others.isEmpty()) tr("Dodaj kolejne konto", "Add another account") else tr("Przełącz konto (${accounts.size})", "Switch account (${accounts.size})"),
                color = Paper,
                fontWeight = FontWeight.Bold,
            )
            val turn by animateFloatAsState(if (open) 180f else 0f, motionSpec(260), label = "chevron")
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                contentDescription = if (open) tr("Zwiń", "Collapse") else tr("Rozwiń", "Expand"),
                tint = Mist,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn },
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = fadeIn(motionSpec(220)) + expandVertically(motionSpec(280)),
            exit = fadeOut(motionSpec(160)) + shrinkVertically(motionSpec(240)),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                others.forEach { acc ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.5.dp, Line, RoundedCornerShape(14.dp))
                            .clickable { open = false; onSwitch(acc) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                acc.name.ifBlank { acc.email },
                                color = Paper,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(acc.email, color = Mist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                GhostButton(tr("Zaloguj inne konto", "Sign in to another account"), onClick = onAdd)
                Spacer(Modifier.height(8.dp))
                Lead(
                    tr(
                        "Odcisk palca odblokowuje aplikację. Konta wybierasz tutaj — " +
                            "każde ma własne rozmowy i własne saldo.",
                        "Your fingerprint unlocks the app. You pick the account here — " +
                            "each has its own chats and its own balance.",
                    ),
                )
            }
        }
    }
}

@Composable
fun AccountTab(
    user: User?,
    usage: Usage?,
    plan: Plan?,
    busy: Boolean,
    accounts: List<Account>,
    onSwitch: (Account) -> Unit,
    onAddAccount: () -> Unit,
    onSettings: () -> Unit,
    onTopUp: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Pad),
    ) {
        SectionTitle(tr("Konto", "Account"), Modifier.padding(top = 10.dp, bottom = 18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(58.dp).clip(CircleShape).background(Paper),
                contentAlignment = Alignment.Center,
            ) {
                val picture = user?.picture
                if (picture != null) {
                    AsyncImage(model = picture, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        (user?.name?.ifBlank { user.email } ?: "?").take(1).uppercase(),
                        color = Ink, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    user?.name?.ifBlank { user.email.substringBefore("@") } ?: "",
                    color = Paper, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                )
                Text(user?.email ?: "", color = Mist, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        AccountSwitcher(
            accounts = accounts,
            activeId = user?.id,
            onSwitch = onSwitch,
            onAdd = onAddAccount,
        )

        // Plan konta
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.5.dp, Line, RoundedCornerShape(20.dp))
                .padding(18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("PLAN", color = Mist, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Paper)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(
                        (plan?.name ?: "—").uppercase(),
                        color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (plan == null) {
                // Serwer nie liczy jeszcze planu (brak /profile) — nie udajemy zera doladowan.
                Text(tr("Plan pojawi się, gdy serwer zacznie go liczyć.", "The plan will appear once the server starts tracking it."), color = Mist, fontSize = 14.sp)
            } else {
                Text(
                    usd(plan.paidUsd),
                    color = Paper, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                )
                Text(tr("łącznie doładowań, bez testowych", "topped up in total, excluding test funds"), color = Mist, fontSize = 13.sp)
            }
            if (plan?.nextName != null) {
                Spacer(Modifier.height(16.dp))
                val ratio = if (plan.nextAtUsd > 0) (plan.paidUsd / plan.nextAtUsd).toFloat().coerceIn(0f, 1f) else 0f
                // Pasek wypelnia sie po wejsciu na ekran — widac, ile brakuje.
                val fill = remember { Animatable(if (Prefs.animations) 0f else ratio) }
                LaunchedEffect(ratio) { fill.animateTo(ratio, motionSpec(900)) }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Line)) {
                    Box(Modifier.fillMaxWidth(fill.value).fillMaxHeight().clip(RoundedCornerShape(50)).background(Paper))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Do planu ${plan.nextName} brakuje ${usd(plan.missingUsd)}.", "${usd(plan.missingUsd)} more to reach the ${plan.nextName} plan."),
                    color = Mist, fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        StatRow(tr("Saldo", "Balance"), usage?.let { usd(it.balanceUsd) } ?: user?.let { usd(it.balanceUsd) } ?: "–")
        StatRow(tr("Wydane w 30 dni", "Spent in 30 days"), usage?.let { usd(it.spentUsd) } ?: "–")
        StatRow(tr("Tokeny w 30 dni", "Tokens in 30 days"), usage?.let { int(it.tokens) } ?: "–")
        StatRow(tr("Doładowania", "Top-ups"), plan?.topups?.toString() ?: "–")

        Spacer(Modifier.height(24.dp))
        PrimaryButton(tr("Doładuj saldo", "Top up balance"), onClick = onTopUp)
        Spacer(Modifier.height(10.dp))
        GhostButton(tr("Ustawienia", "Settings"), onClick = onSettings)
        Spacer(Modifier.height(10.dp))
        GhostButton(tr("Wyloguj się", "Sign out"), busy = busy, onClick = onLogout)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Mist)
            Text(value, color = Paper, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}

// ---------- Dolna nawigacja ----------
enum class Tab(private val pl: String, private val en: String, val icon: Int) {
    Chat("Czat", "Chat", R.drawable.ic_chat),
    Agents("Agenci", "Agents", R.drawable.ic_agents),
    Shop("Sklep", "Shop", R.drawable.ic_shop),
    Browser("Sieć", "Web", R.drawable.ic_web),
    Account("Konto", "Account", R.drawable.ic_user);

    val label: String get() = tr(pl, en)
}

@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.values().forEach { tab ->
            val on = tab == current
            val weight by animateFloatAsState(if (on) 1.9f else 1f, motionSpring(0.8f, 320f), label = "navWeight")
            val bg by animateColorAsState(if (on) Paper else Ink, motionSpec(280), label = "navBg")
            val fg by animateColorAsState(if (on) Ink else Mist, motionSpec(280), label = "navFg")
            Row(
                Modifier
                    .weight(weight)
                    .height(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(tab) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(tab.icon),
                    contentDescription = tab.label,
                    tint = fg,
                    modifier = Modifier.size(21.dp),
                )
                AnimatedVisibility(visible = on) {
                    Text(
                        tab.label,
                        color = fg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun TopBalance(balance: Double, bump: Int, onSettings: () -> Unit, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(bump) {
        if (bump > 0 && Prefs.animations) {
            scale.animateTo(1.1f, tween(200))
            scale.animateTo(1f, spring())
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Pad, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ghost(size = 32.dp)
            Spacer(Modifier.width(8.dp))
            Text("CYPHR", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .clip(RoundedCornerShape(50))
                .border(1.5.dp, Line, RoundedCornerShape(50))
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(usd(balance), color = Paper, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(50))
                .border(1.5.dp, Line, RoundedCornerShape(50))
                .clickable { onSettings() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_settings), tr("Ustawienia", "Settings"), tint = Paper, modifier = Modifier.size(19.dp))
        }
        }
    }
}
