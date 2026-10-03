package pl.cyphr.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

private data class QuickLink(val title: String, val url: String)

/**
 * WebView zyje dluzej niz zakladka. Wczesniej przelaczenie na czat (np. zeby zapytac
 * o strone) niszczylo przegladarke i po powrocie trzeba bylo otwierac wszystko od nowa.
 */
class BrowserHolder {
    var view: WebView? = null

    fun destroy() {
        view?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
        view = null
    }
}

private fun quickLinks() = listOf(
    QuickLink("DuckDuckGo", "https://duckduckgo.com"),
    QuickLink("Wikipedia", tr("https://pl.wikipedia.org", "https://en.wikipedia.org")),
    QuickLink("GitHub", "https://github.com"),
    QuickLink("YouTube", "https://youtube.com"),
    QuickLink("Hacker News", "https://news.ycombinator.com"),
    QuickLink("CYPHR", "https://cyphr.com.pl"),
)

/**
 * Przegladarka: pasek adresu w formie pigulki, wlasny dolny pasek narzedzi
 * i asystent, ktory czyta otwarta strone.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserTab(
    holder: BrowserHolder,
    agent: String?,
    asking: Boolean,
    answer: String?,
    onCloseAnswer: () -> Unit,
    onAsk: (pageText: String, url: String, question: String) -> Unit,
) {
    // Stan paska odtwarzamy z zachowanego WebView — po powrocie na zakladke strona jest tam, gdzie byla.
    val kept = holder.view
    var webView by remember { mutableStateOf(kept) }
    var url by remember { mutableStateOf(kept?.url.orEmpty()) }
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var loading by remember { mutableStateOf(false) }
    var canBack by remember { mutableStateOf(kept?.canGoBack() == true) }
    var canForward by remember { mutableStateOf(kept?.canGoForward() == true) }
    var secure by remember { mutableStateOf(kept?.url?.startsWith("https") ?: true) }
    var started by remember { mutableStateOf(!kept?.url.isNullOrBlank()) }

    // Systemowe „wstecz” cofa strone, a dopiero bez historii wychodzi z przegladarki.
    androidx.activity.compose.BackHandler(enabled = canBack && !editing) { webView?.goBack() }
    var askOpen by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var pendingAsk by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    fun go(target: String) {
        started = true
        editing = false
        webView?.loadUrl(normalize(target))
    }

    Column(Modifier.fillMaxSize()) {
        // Pasek adresu
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Raise)
                    .clickable(enabled = !editing) { draft = url; editing = true }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (editing) {
                    BasicAddressInput(
                        value = draft,
                        onValue = { draft = it },
                        onGo = { go(draft) },
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (started) {
                            Icon(
                                painterResource(if (secure) R.drawable.ic_lock else R.drawable.ic_close),
                                null, tint = Mist, modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (started) host(url) else tr("Wpisz adres albo szukaj", "Type an address or search"),
                            color = if (started) Paper else Mist,
                            fontSize = 15.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
            IconPill(if (loading) R.drawable.ic_close else R.drawable.ic_reload) {
                if (loading) webView?.stopLoading() else webView?.reload()
            }
        }

        val bar by animateFloatAsState(if (loading) progress else 0f, tween(180), label = "progress")
        Box(Modifier.fillMaxWidth().height(2.dp).background(Ink)) {
            Box(Modifier.fillMaxWidth(bar).fillMaxHeight().background(Paper))
        }

        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    val view = holder.view ?: WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.safeBrowsingEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                    }.also { holder.view = it }
                    // Zachowany widok moze wciaz wisiec pod starym ekranem.
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    // Obslugi podpinamy za kazdym razem na nowo — pisza do stanu tej kompozycji.
                    view.apply {
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                            ): Boolean {
                                val target = request?.url?.toString() ?: return false
                                // Zwykly http jest odrzucany, tak samo jak w reszcie aplikacji
                                return if (target.startsWith("http://")) {
                                    view?.loadUrl(target.replaceFirst("http://", "https://"))
                                    true
                                } else false
                            }
                            override fun onPageStarted(view: WebView?, address: String?, favicon: Bitmap?) {
                                loading = true
                                address?.let { url = it; secure = it.startsWith("https") }
                            }
                            override fun onPageFinished(view: WebView?, address: String?) {
                                loading = false
                                address?.let { url = it; secure = it.startsWith("https") }
                                canBack = view?.canGoBack() == true
                                canForward = view?.canGoForward() == true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }
                        }
                        webView = this
                    }
                },
                update = { view ->
                    val desktop = Prefs.desktopMode
                    view.settings.userAgentString =
                        if (desktop) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36"
                        else null
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (!started) {
                StartPage(onOpen = { go(it) }, onSearch = { go(it) })
            }
        }

        // Dolny pasek narzedzi
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconPill(R.drawable.ic_back, enabled = canBack) { webView?.goBack() }
            IconPill(R.drawable.ic_forward, enabled = canForward) { webView?.goForward() }
            IconPill(R.drawable.ic_home) { go(Prefs.homePage) }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Paper)
                    .clickable { askOpen = true }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ghost(size = 20.dp, modifier = Modifier.padding(end = 8.dp))
                Text(tr("Zapytaj AI", "Ask AI"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }

    if (askOpen) {
        ModalBottomSheet(onDismissRequest = { askOpen = false }, containerColor = Raise, contentColor = Paper) {
            Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
                SectionTitle(tr("Zapytaj o stronę", "Ask about the page"))
                Spacer(Modifier.height(8.dp))
                Lead(
                    when {
                        agent == null -> tr("Najpierw wybierz agenta w zakładce Agenci.", "First pick an agent in the Agents tab.")
                        !started -> tr("Najpierw otwórz jakąś stronę.", "Open a page first.")
                        else -> "Agent: $agent · ${host(url)}"
                    },
                )
                Spacer(Modifier.height(16.dp))
                Field(question, tr("Pytanie", "Question"), { question = it })
                Spacer(Modifier.height(10.dp))
                Lead(tr("Puste pytanie oznacza streszczenie strony.", "An empty question means a summary of the page."))
                Spacer(Modifier.height(16.dp))
                PrimaryButton(tr("Wyślij", "Send"), busy = asking, enabled = agent != null && started) {
                    val view = webView ?: return@PrimaryButton
                    view.evaluateJavascript("(function(){return document.body.innerText.slice(0,6000)})()") { raw ->
                        val text = try { JSONObject("{\"t\":$raw}").optString("t") } catch (e: Exception) { "" }
                        askOpen = false
                        val payload = Triple(text, url, question.ifBlank { tr("Streść tę stronę po polsku.", "Summarize this page in English.") })
                        question = ""
                        if (Prefs.needsAsk(Ask.AiPage)) pendingAsk = payload
                        else onAsk(payload.first, payload.second, payload.third)
                    }
                }
            }
        }
    }

    pendingAsk?.let { (text, address, q) ->
        PermissionDialog(
            title = tr("Wysłać stronę do modelu?", "Send the page to the model?"),
            what = host(address),
            detail = tr(
                "Do agenta pójdzie ${text.length} znaków treści strony oraz pytanie: $q",
                "The agent will get ${text.length} characters of the page's content and the question: $q",
            ),
            // Wysylka strony pyta zawsze, wiec „Zawsze” niczego by nie zapamietalo — nie udajemy.
            allowAlways = false,
            onAllowOnce = { onAsk(text, address, q); pendingAsk = null },
            onAllowAlways = {},
            onDeny = { pendingAsk = null },
        )
    }

    if (answer != null) {
        ModalBottomSheet(onDismissRequest = onCloseAnswer, containerColor = Raise, contentColor = Paper) {
            Column(
                Modifier
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 28.dp)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ghost(size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    SectionTitle(tr("Odpowiedź", "Answer"))
                }
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    ChatMarkdown(answer, Paper)
                }
                Spacer(Modifier.height(20.dp))
                GhostButton(tr("Zamknij", "Close"), onClick = onCloseAnswer)
            }
        }
    }

    AnimatedVisibility(visible = asking, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.clip(RoundedCornerShape(24.dp)).background(Raise).padding(26.dp),
                contentAlignment = Alignment.Center,
            ) { Ghost(size = 64.dp, floating = true) }
        }
    }
}

@Composable
private fun BasicAddressInput(value: String, onValue: (String) -> Unit, onGo: () -> Unit) {
    TextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onGo() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedTextColor = Paper, unfocusedTextColor = Paper, cursorColor = Paper,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun IconPill(icon: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(50))
            .border(1.5.dp, Line, RoundedCornerShape(50))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, tint = if (enabled) Paper else Line, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun StartPage(onOpen: (String) -> Unit, onSearch: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Ink).padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Ghost(size = 92.dp, floating = true)
        Spacer(Modifier.height(18.dp))
        Text(tr("Przeglądaj z agentem", "Browse with an agent"), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Paper)
        Spacer(Modifier.height(8.dp))
        Lead(tr("Otwórz stronę, a potem zapytaj o nią asystenta.", "Open a page, then ask the assistant about it."), center = true)
        Spacer(Modifier.height(22.dp))
        Field(query, tr("Szukaj albo wpisz adres", "Search or type an address"), { query = it })
        Spacer(Modifier.height(12.dp))
        PrimaryButton(tr("Otwórz", "Open")) { if (query.isNotBlank()) onSearch(query) }
        Spacer(Modifier.height(26.dp))
        quickLinks().chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { link ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.5.dp, Line, RoundedCornerShape(16.dp))
                            .clickable { onOpen(link.url) }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(link.title, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun host(url: String): String = try {
    java.net.URI(url).host?.removePrefix("www.") ?: url
} catch (e: Exception) { url }

private fun normalize(input: String): String = when {
    input.startsWith("http://") || input.startsWith("https://") -> input
    input.contains(".") && !input.contains(" ") -> "https://$input"
    else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(input, "UTF-8")
}
