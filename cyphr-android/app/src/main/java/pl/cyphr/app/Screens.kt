package pl.cyphr.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    onSubmit: (register: Boolean, email: String, password: String, name: String) -> Unit,
) {
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val enter = remember { Animatable(0f) }
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
        Divider("albo e-mailem")
        Spacer(Modifier.height(16.dp))
        Segmented(register) { register = it }
        Spacer(Modifier.height(16.dp))

        if (register) {
            Field(name, "Imię", { name = it })
            Spacer(Modifier.height(12.dp))
        }
        Field(email, "E-mail", { email = it }, keyboard = KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        Field(password, "Hasło", { password = it }, password = true)
        if (register) {
            Spacer(Modifier.height(6.dp))
            Text("Co najmniej 8 znaków.", color = Mist, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        ErrorText(error)
        Spacer(Modifier.height(12.dp))
        GhostButton(if (register) "Załóż konto" else "Zaloguj się", busy = busy) {
            onSubmit(register, email.trim().lowercase(), password, name.trim())
        }
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
        Text("Zaloguj się przez Google", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Kod z e-maila ----------
@Composable
fun VerifyScreen(
    email: String,
    busy: Boolean,
    error: String?,
    cooldown: Int,
    onBack: () -> Unit,
    onResend: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

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
            Icon(painterResource(R.drawable.ic_back), contentDescription = "Wróć", tint = Paper, modifier = Modifier.size(20.dp))
        }
        Ghost(size = 76.dp, floating = true, modifier = Modifier.padding(vertical = 12.dp))
        SectionTitle("Sprawdź skrzynkę")
        Spacer(Modifier.height(8.dp))
        Lead("Wysłaliśmy 6-cyfrowy kod na $email. Kod jest ważny 15 minut.")
        Spacer(Modifier.height(24.dp))

        BasicCodeField(code, focus) { value ->
            code = value
            if (value.length == 6) onSubmit(value)
        }

        Spacer(Modifier.height(16.dp))
        ErrorText(error)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Potwierdź", busy = busy) { onSubmit(code) }
        Spacer(Modifier.height(10.dp))
        GhostButton(
            if (cooldown > 0) "Wyślij ponownie za $cooldown s" else "Wyślij kod ponownie",
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
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(1.5.dp, if (cursor) Paper else if (filled) Mist else Line, RoundedCornerShape(14.dp)),
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
        Text("ZUŻYCIE", color = Mist, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatCell("Tokeny / 30 dni", usage?.tokens?.toString() ?: "—")
            StatCell("Wydane / 30 dni", usage?.let { usd(it.spentUsd) } ?: "—")
        }
        Spacer(Modifier.height(14.dp))
        Divider(color = Line, thickness = 1.dp)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatCell("Ostatnie wejście", lastTokens?.first?.toString() ?: "—")
            StatCell("Ostatnie wyjście", lastTokens?.second?.toString() ?: "—")
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
    var query by remember { mutableStateOf("") }

    // Ulubione na gorze, reszta alfabetycznie. Katalog dostawcy potrafi miec setki
    // pozycji, wiec bez tego wybor jest przewijaniem na oslep.
    val shown = remember(agents, query) {
        val q = query.trim().lowercase()
        agents
            .filter { q.isBlank() || it.name.lowercase().contains(q) || it.id.lowercase().contains(q) }
            .sortedWith(
                compareByDescending<Agent> { Prefs.isFavourite(it.id) }
                    .thenBy { it.name.lowercase() },
            )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Pad)) {
        SectionTitle("Agenci", Modifier.padding(top = 10.dp, bottom = 16.dp))

        AgentStats(usage, lastTokens)
        Spacer(Modifier.height(18.dp))

        if (agents.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Ghost(size = 110.dp, floating = true)
                Spacer(Modifier.height(22.dp))
                Text("Brak agentów", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Paper)
                Spacer(Modifier.height(6.dp))
                Lead("Spróbuj odświeżyć listę.", center = true)
                Spacer(Modifier.height(22.dp))
                GhostButton("Odśwież", Modifier.width(180.dp), busy = busy, onClick = onRefresh)
            }
        } else {
            // Wyszukiwarka ma sens dopiero przy dluzszej liscie — przy kilku pozycjach
            // to tylko zajete miejsce.
            if (agents.size > 6) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Field(query, "Szukaj wśród ${agents.size}", { query = it })
                    }
                    Spacer(Modifier.width(10.dp))
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        Icon(painterResource(R.drawable.ic_reload), "Odśwież", tint = Mist, modifier = Modifier.size(19.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (shown.isEmpty()) {
                Lead("Nic nie pasuje do „$query”.", center = true)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.id }) { agent ->
                        AgentRow(
                            agent = agent,
                            selected = agent.id == selected,
                            favourite = Prefs.isFavourite(agent.id),
                            onPick = { onPick(agent) },
                            onFavourite = { Prefs.toggleFavourite(agent.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentRow(
    agent: Agent,
    selected: Boolean,
    favourite: Boolean,
    onPick: () -> Unit,
    onFavourite: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, if (selected) Paper else Line, RoundedCornerShape(18.dp))
            .clickable { onPick() }
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                agent.name,
                color = Paper,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (agent.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    agent.description,
                    color = Mist,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onFavourite) {
            Icon(
                painterResource(R.drawable.ic_plus),
                if (favourite) "Usuń z ulubionych" else "Dodaj do ulubionych",
                tint = if (favourite) Paper else Line,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---------- Sklep ----------
@Composable
fun ShopTab(shop: Shop?, holder: String, onPick: (Pack) -> Unit) {
    var preview by remember(shop) { mutableStateOf(shop?.packages?.getOrNull(1)) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Pad),
    ) {
        Spacer(Modifier.height(6.dp))
        CreditCard3D(
            label = preview?.name?.let { "Pakiet $it" } ?: "Doładowanie",
            amount = preview?.let { usd(it.amountUsd) } ?: "—",
            holder = holder,
        )
        Spacer(Modifier.height(20.dp))
        SectionTitle("Doładuj saldo")
        Spacer(Modifier.height(8.dp))
        Lead(
            when {
                shop == null -> "Wczytuję pakiety."
                shop.testLeftUsd > 0 -> "Tryb testowy. Nic nie płacisz. Zostało ${usd(shop.testLeftUsd)} testowych środków."
                else -> "Tryb testowy. Limit testowych środków jest wyczerpany, więc zakupy są tylko symulowane."
            },
        )
        Spacer(Modifier.height(18.dp))
        shop?.packages?.forEach { pack ->
            val chosen = pack.id == preview?.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (chosen) Paper else Ink)
                    .border(1.5.dp, if (chosen) Paper else Line, RoundedCornerShape(20.dp))
                    .clickable { if (chosen) onPick(pack) else preview = pack }
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(pack.name, color = if (chosen) Color(0xFF555555) else Mist, fontSize = 15.sp)
                    Text(
                        usd(pack.amountUsd),
                        color = if (chosen) Ink else Paper,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (chosen) Ink else Paper)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (chosen) "Kup" else "Wybierz",
                        color = if (chosen) Paper else Ink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
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
                if (others.isEmpty()) "Dodaj kolejne konto" else "Przełącz konto (${accounts.size})",
                color = Paper,
                fontWeight = FontWeight.Bold,
            )
            Text(if (open) "▴" else "▾", color = Mist)
        }

        if (open) {
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
            GhostButton("Zaloguj inne konto", onClick = onAdd)
            Spacer(Modifier.height(8.dp))
            Lead(
                "Odcisk palca odblokowuje aplikację. Konta wybierasz tutaj — " +
                    "każde ma własne rozmowy i własne saldo.",
            )
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
        SectionTitle("Konto", Modifier.padding(top = 10.dp, bottom = 18.dp))

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
            Text(
                usd(plan?.paidUsd ?: 0.0),
                color = Paper, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
            )
            Text("łącznie doładowań, bez testowych", color = Mist, fontSize = 13.sp)
            if (plan?.nextName != null) {
                Spacer(Modifier.height(16.dp))
                val ratio = if (plan.nextAtUsd > 0) (plan.paidUsd / plan.nextAtUsd).toFloat().coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Line)) {
                    Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().background(Paper))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Do planu ${plan.nextName} brakuje ${usd(plan.missingUsd)}.",
                    color = Mist, fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        StatRow("Saldo", usage?.let { usd(it.balanceUsd) } ?: user?.let { usd(it.balanceUsd) } ?: "–")
        StatRow("Wydane w 30 dni", usage?.let { usd(it.spentUsd) } ?: "–")
        StatRow("Tokeny w 30 dni", usage?.let { int(it.tokens) } ?: "–")
        StatRow("Doładowania", plan?.topups?.toString() ?: "–")

        Spacer(Modifier.height(24.dp))
        PrimaryButton("Doładuj saldo", onClick = onTopUp)
        Spacer(Modifier.height(10.dp))
        GhostButton("Ustawienia", onClick = onSettings)
        Spacer(Modifier.height(10.dp))
        GhostButton("Wyloguj się", busy = busy, onClick = onLogout)
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
enum class Tab(val label: String, val icon: Int) {
    Chat("Czat", R.drawable.ic_chat),
    Agents("Agenci", R.drawable.ic_agents),
    Shop("Sklep", R.drawable.ic_shop),
    Browser("Sieć", R.drawable.ic_web),
    Account("Konto", R.drawable.ic_user),
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
            val weight by animateFloatAsState(if (on) 1.9f else 1f, motionSpec(360), label = "navWeight")
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
        Ghost(size = 34.dp)
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
            Icon(painterResource(R.drawable.ic_settings), "Ustawienia", tint = Paper, modifier = Modifier.size(19.dp))
        }
        }
    }
}
