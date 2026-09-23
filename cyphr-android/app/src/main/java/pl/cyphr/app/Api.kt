package pl.cyphr.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class ApiError(message: String, val code: String? = null, val status: Int = 0) : Exception(message)

/**
 * Zapytanie, ktore da sie przerwac. Przy execute() przerwana praca (wylogowanie, usuniecie
 * rozmowy) czekala do konca odpowiedzi serwera, nawet minute — teraz zamyka polaczenie od razu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        // Po przerwaniu blad juz nikogo nie obchodzi — takie wznowienie jest pomijane.
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

        // Odpowiedz, ktora doszla za pozno, zamykamy — inaczej zostaje otwarte polaczenie.
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { response.close() }
    })
}

data class User(
    val id: Long,
    val email: String,
    val name: String,
    val picture: String?,
    val balanceUsd: Double,
)

/**
 * Modele oferowane w aplikacji — dokladnie te dwa. Identyfikator idzie do serwera,
 * ktory przekazuje go dalej; uzytkownik widzi wylacznie nazwe CYPHR i opis.
 * Pochodzenia modeli aplikacja nigdzie nie pokazuje (patrz Persona).
 */
val CATALOG = listOf(
    Agent(
        "glm-5.3-flash-uncensored",
        "CYPHR Flash",
        "Szybkie odpowiedzi i bardzo długa pamięć rozmowy — 262 tys. tokenów.",
    ),
    Agent(
        "qwen3.8-27b-uncensored",
        "CYPHR Pro",
        "Staranniejsze odpowiedzi przy trudniejszych zadaniach — 131 tys. tokenów pamięci.",
    ),
)

/** Model wybierany, gdy uzytkownik jeszcze zadnego nie wskazal. */
const val DEFAULT_AGENT = "glm-5.3-flash-uncensored"

/**
 * Laczy katalog aplikacji z lista, ktora dopuszcza serwer.
 *
 * Pokazujemy wylacznie modele z katalogu — pod nazwami CYPHR. Model spoza katalogu
 * przyszedlby z nazwa i opisem dostawcy, a /v1/models potrafi oddac caly jego cennik.
 * Z serwera bierzemy sam fakt dostepnosci i aktualna cene. Gdy serwer milczy albo
 * nie zna zadnego z naszych modeli, zostaje caly katalog, zeby bylo w co kliknac.
 */
fun mergeAgents(remote: List<Agent>): List<Agent> {
    val byId = remote.associateBy { it.id }
    val offered = CATALOG.filter { it.id in byId }
    if (offered.isEmpty()) return CATALOG
    return offered.map { c -> c.copy(price = byId.getValue(c.id).price) }
}

data class Pack(val id: String, val name: String, val amountUsd: Double)
/** Model do wyboru. [price] przychodzi z serwera, nazwa i opis — z katalogu. */
data class Agent(val id: String, val name: String, val description: String, val price: String? = null)
/** Wiadomosc rozmowy. [attachments] — zdjecia, pliki albo obraz stworzony przez model. */
data class ChatMessage(
    val text: String,
    val fromUser: Boolean,
    val attachments: List<Attachment> = emptyList(),
)
data class Shop(val packages: List<Pack>, val testLeftUsd: Double)
data class Usage(val balanceUsd: Double, val spentUsd: Double, val tokens: Long)

/** Odpowiedz modelu wraz z rzeczywistym zuzyciem zwroconym przez dostawce. */
data class Reply(val text: String, val inTokens: Int, val outTokens: Int)

/**
 * Przyblizenie liczby tokenow po stronie telefonu, zanim zapytanie poleci.
 * Prawdziwy podzial robi tokenizer modelu — to tylko wskazowka rzedu wielkosci.
 */
fun estimateTokens(text: String): Int =
    if (text.isBlank()) 0 else kotlin.math.ceil(text.length / 3.5).toInt()

/** Tyle mniej wiecej kosztuje modela jeden obraz na wejsciu (zdjecie albo strona PDF-a). */
const val IMAGE_TOKENS = 900

/** Szacunek dla calej wiadomosci: tekst, tresc plikow i obrazy. */
fun messageTokens(m: ChatMessage): Int = estimateTokens(m.text) + m.attachments.sumOf { a ->
    when (a.kind) {
        Attachment.Kind.Text -> estimateTokens(a.text)
        Attachment.Kind.Image, Attachment.Kind.Pdf -> IMAGE_TOKENS * a.files.size.coerceAtLeast(1)
        Attachment.Kind.Generated -> estimateTokens(a.prompt) + 10
    }
}

/** Powyzej tylu tokenow swiezej rozmowy zwijamy jej starsza czesc w notatke. */
const val FOLD_ABOVE = 3_000

/** Tyle ostatnich wiadomosci zostaje doslownie, nawet przy zwijaniu. */
const val KEEP_VERBATIM = 8

/**
 * Pamiec rozmowy. Zamiast slac caly zapis przy kazdej wiadomosci, starsze wymiany
 * zwijamy w notatke z ustaleniami, a doslownie idzie tylko swiezy fragment.
 * [folded] mowi, ile pierwszych wiadomosci reprezentuje juz [summary].
 */
data class Memory(val summary: String = "", val folded: Int = 0)

/** Wiadomosci, ktore jeszcze nie zostaly zwiniete w notatke. */
fun freshOf(messages: List<ChatMessage>, memory: Memory): List<ChatMessage> =
    messages.drop(memory.folded.coerceAtMost(messages.size))

/** Czy swieza czesc urosla na tyle, ze oplaca sie ja zwinac. */
fun shouldFold(messages: List<ChatMessage>, memory: Memory): Boolean {
    val fresh = freshOf(messages, memory)
    if (fresh.size <= KEEP_VERBATIM) return false
    val older = fresh.dropLast(KEEP_VERBATIM)
    return older.sumOf { messageTokens(it) } > FOLD_ABOVE
}
data class Plan(
    val id: String,
    val name: String,
    val paidUsd: Double,
    val testUsd: Double,
    val topups: Int,
    val nextName: String?,
    val nextAtUsd: Double,
    val missingUsd: Double,
)
data class Profile(val user: User, val plan: Plan)

object Api {
    private const val KEY_TOKEN = "token"
    private const val KEY_USER = "user"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()
    private var token: String? = null
    private lateinit var app: Context

    fun load(ctx: Context) {
        app = ctx.applicationContext
        token = SecureStore.get(KEY_TOKEN)
    }

    /** Adres serwera. Mozna go zmienic w ustawieniach aplikacji. */
    /** Zawsze serwer CYPHR — klient nie ustawia adresu sam. */
    private fun base(): String = BuildConfig.BASE_URL

    fun token(): String? = token

    fun saveToken(ctx: Context, value: String?) {
        token = value
        SecureStore.put(KEY_TOKEN, value)
        if (value == null) SecureStore.put(KEY_USER, null)
    }

    /**
     * Ostatnio znany profil, w szyfrowanym schowku obok tokenu. Sluzy do wejscia
     * do aplikacji, gdy token jest wazny, ale serwer chwilowo nie odpowiada —
     * bez tego awaria backendu wygladalaby jak wylogowanie.
     */
    private fun cacheUser(u: User) {
        SecureStore.put(
            KEY_USER,
            JSONObject()
                .put("id", u.id).put("email", u.email).put("name", u.name)
                .put("picture", u.picture ?: "").put("balance_usd", u.balanceUsd)
                .toString(),
        )
    }

    fun cachedUser(): User? = SecureStore.get(KEY_USER)?.let {
        try {
            val o = JSONObject(it)
            User(
                id = o.optLong("id"),
                email = o.optString("email"),
                name = o.optString("name"),
                picture = o.optString("picture").ifBlank { null },
                balanceUsd = o.optDouble("balance_usd", 0.0),
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun call(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        auth: String? = token,
    ): JSONObject {
        val builder = Request.Builder().url(base() + path)
        auth?.let { builder.header("Authorization", "Bearer $it") }
        when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(json))
            else -> builder.get()
        }
        val response = try {
            client.newCall(builder.build()).await()
        } catch (e: IOException) {
            throw ApiError("Brak połączenia z internetem.", "offline")
        }
        // use na zewnatrz: odpowiedz zamyka sie takze wtedy, gdy praca zostanie przerwana,
        // zanim zaczniemy ja czytac.
        return response.use {
            withContext(Dispatchers.IO) {
                val text = it.body?.string().orEmpty()
                val data = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (!it.isSuccessful) {
                    // Limiter i awarie serwera odpowiadaja zwyklym tekstem, nie JSON-em.
                    // Bez tego uzytkownik widzialby tylko "Cos poszlo nie tak".
                    val fromServer = data.optString("message").ifBlank { null }?.let { Persona.scrub(it) }
                    val retryAfter = it.header("Retry-After")?.trim()?.toIntOrNull()
                    throw ApiError(
                        fromServer ?: explain(it.code, retryAfter),
                        data.optString("error").ifBlank { null },
                        it.code,
                    )
                }
                data
            }
        }
    }

    /** Cena po polsku: przecinek, dwie cyfry. */
    private fun money(v: Double): String = String.format(java.util.Locale("pl"), "%.2f", v)

    /** Zrozumiala wiadomosc dla bledu, przy ktorym serwer nie przyslal swojej. */
    internal fun explain(status: Int, retryAfter: Int?): String = when (status) {
        401 -> "Sesja wygasła. Zaloguj się ponownie."
        403 -> "Brak dostępu do tej funkcji."
        404 -> "Serwer nie zna tej funkcji. Zaktualizuj aplikację."
        408, 504 -> "Serwer nie odpowiedział na czas. Spróbuj jeszcze raz."
        // Limiter liczy zapytania, a nie bledne kody czy hasla. Dawny komunikat
        // "za duzo prob" brzmial, jakby uzytkownik sie pomylil — a wystarczyly
        // trzy zapytania pod rzad, zeby go zobaczyc.
        429 -> {
            val za = retryAfter?.let { s -> if (s >= 60) "${s / 60} min" else "$s s" }
            if (za != null) "Za szybko pod rząd. Odczekaj $za i spróbuj ponownie."
            else "Za szybko pod rząd. Odczekaj chwilę i spróbuj ponownie."
        }
        502, 503 -> "Serwer się restartuje. Spróbuj za moment."
        in 500..599 -> "Serwer ma chwilową awarię. Spróbuj za moment."
        else -> "Coś poszło nie tak. Spróbuj ponownie."
    }

    private fun user(o: JSONObject): User = User(
        id = o.optLong("id"),
        email = o.optString("email"),
        name = o.optString("name"),
        picture = o.optString("picture").ifBlank { null }.takeIf { it != "null" },
        balanceUsd = o.optDouble("balance_usd", 0.0),
    )

    /** Zwraca uzytkownika po zalogowaniu albo null, gdy serwer prosi o kod z e-maila. */
    suspend fun register(ctx: Context, email: String, password: String, name: String): User? {
        val r = call("/auth/register", "POST", JSONObject().put("email", email).put("password", password).put("name", name))
        return session(ctx, r)
    }

    suspend fun login(ctx: Context, email: String, password: String): User? =
        session(ctx, call("/auth/login", "POST", JSONObject().put("email", email).put("password", password)))

    suspend fun google(ctx: Context, idToken: String): User? =
        session(ctx, call("/auth/google", "POST", JSONObject().put("id_token", idToken)))

    suspend fun verify(ctx: Context, email: String, code: String): User? =
        session(ctx, call("/auth/verify", "POST", JSONObject().put("email", email).put("code", code)))

    suspend fun resend(email: String) {
        call("/auth/resend", "POST", JSONObject().put("email", email))
    }

    /**
     * Prosba o kod do zmiany zapomnianego hasla. Serwer odpowiada tak samo
     * niezaleznie od tego, czy konto istnieje — inaczej dalby sie odpytac,
     * ktore adresy sa zarejestrowane.
     */
    suspend fun forgot(email: String) {
        call("/auth/forgot", "POST", JSONObject().put("email", email))
    }

    /**
     * Kod z maila plus nowe haslo. Sama zmiana nie tworzy sesji — aplikacja
     * loguje sie potem normalnie, dzieki czemu serwer nie musi duplikowac
     * wydawania tokenow.
     */
    suspend fun reset(email: String, code: String, password: String) {
        call(
            "/auth/reset",
            "POST",
            JSONObject().put("email", email).put("code", code).put("password", password),
        )
    }

    private fun session(ctx: Context, r: JSONObject): User? {
        val t = r.optString("token").ifBlank { null } ?: return null
        saveToken(ctx, t)
        val u = user(r.optJSONObject("user") ?: JSONObject())
        // Podreczna kopia profilu i lista kont to wygoda, nie warunek zalogowania.
        // Gdyby schowek odmowil, logowanie ma przejsc mimo to.
        try {
            cacheUser(u)
            Accounts.upsert(Account(u.id, u.email, u.name, t))
        } catch (_: Exception) {
        }
        return u
    }

    /** Przelacza aplikacje na inne zapamietane konto bez pytania o haslo. */
    fun useAccount(ctx: Context, account: Account) {
        saveToken(ctx, account.token)
        cacheUser(User(account.id, account.email, account.name, null, 0.0))
    }

    /**
     * Profil konta. [auth] — sesja inna niz biezaca (odpowiedz w tle po przelaczeniu konta):
     * wtedy profil nie trafia do schowka, bo tam lezy profil konta, ktore jest na ekranie.
     */
    suspend fun me(auth: String? = token): User {
        val u = user(call("/me", auth = auth).getJSONObject("user"))
        if (auth == token) cacheUser(u)
        return u
    }

    suspend fun profile(): Profile {
        val r = call("/profile")
        val p = r.getJSONObject("plan")
        val next = p.optJSONObject("next")
        return Profile(
            user = user(r.getJSONObject("user")),
            plan = Plan(
                id = p.optString("id", "free"),
                name = p.optString("name", "Free"),
                paidUsd = p.optDouble("paid_usd", 0.0),
                testUsd = p.optDouble("test_usd", 0.0),
                topups = p.optInt("topups", 0),
                nextName = next?.optString("name"),
                nextAtUsd = next?.optDouble("at_usd", 0.0) ?: 0.0,
                missingUsd = next?.optDouble("missing_usd", 0.0) ?: 0.0,
            ),
        )
    }

    suspend fun usage(): Usage {
        val r = call("/me/usage?limit=1")
        val last = r.getJSONObject("last_30_days")
        return Usage(
            balanceUsd = r.optDouble("balance_usd", 0.0),
            spentUsd = last.optDouble("spent_usd", 0.0),
            tokens = last.optLong("in_tokens") + last.optLong("out_tokens"),
        )
    }

    suspend fun agents(): List<Agent> {
        val arr = call("/agents").optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Agent(o.optString("id"), o.optString("name").ifBlank { o.optString("id") }, o.optString("description"))
        }
    }

    suspend fun shop(): Shop {
        val r = call("/shop/packages")
        val arr = r.optJSONArray("packages")
        val packs = (0 until (arr?.length() ?: 0)).map {
            val o = arr!!.getJSONObject(it)
            Pack(o.optString("id"), o.optString("name"), o.optDouble("amount_usd", 0.0))
        }
        return Shop(packs, r.optDouble("test_left_usd", 0.0))
    }

    /** Zakup testowy. Zwraca pare: nowy stan konta i kwota dopisana do salda. */
    suspend fun buy(packageId: String): Pair<User, Double> {
        val r = call("/shop/buy", "POST", JSONObject().put("package_id", packageId))
        return user(r.getJSONObject("user")) to r.optDouble("credited_usd", 0.0)
    }


    /** Modele, ktore serwer dopuszcza, z aktualna cena. Na liste trafiaja tylko te z [CATALOG]. */
    suspend fun models(): List<Agent> {
        val r = call("/v1/models")
        val arr = r.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val p = o.optJSONObject("pricing")
            val price = p?.let {
                val i = it.optDouble("input_usd_per_m", 0.0)
                val out = it.optDouble("output_usd_per_m", 0.0)
                if (i > 0 || out > 0) "${money(i)} / ${money(out)} $ za mln tokenów" else null
            }
            // Bez owned_by — to nazwa dostawcy, a tej aplikacja nigdzie nie pokazuje.
            Agent(id, o.optString("name").ifBlank { id }, "", price)
        }
    }

    /**
     * Pojedyncza odpowiedz czatu. Historia jest wysylana w calosci. [auth] to sesja,
     * z ktorej salda idzie odpowiedz — domyslnie biezaca.
     */
    suspend fun chat(
        model: String,
        messages: List<ChatMessage>,
        memory: Memory = Memory(),
        toolInstructions: String? = null,
        auth: String? = token,
        /** Czy wysylac obrazy — false po odmowie serwera, wtedy ida same opisy. */
        images: Boolean = true,
    ): Reply {
        val arr = org.json.JSONArray()
        val name = Persona.nameOf(model)
        // Tozsamosc CYPHR idzie pierwsza i nie da sie jej usunac z Ustawien.
        val system = listOfNotNull(Persona.system(name), Prefs.systemPrompt.takeIf { it.isNotBlank() }, toolInstructions)
            .joinToString("\n\n")
        system.takeIf { it.isNotBlank() }?.let {
            arr.put(JSONObject().put("role", "system").put("content", it))
        }
        memory.summary.takeIf { it.isNotBlank() }?.let {
            arr.put(
                JSONObject().put("role", "system").put(
                    "content",
                    "Ustalenia z wcześniejszej części tej rozmowy. Traktuj je jako znane " +
                        "i nie wracaj do nich od zera:\n\n$it",
                ),
            )
        }
        val fresh = freshOf(messages, memory)
        val withImages = if (images) imageMessages(fresh) else emptySet()
        val build = {
            fresh.forEachIndexed { i, m ->
                arr.put(
                    JSONObject()
                        .put("role", if (m.fromUser) "user" else "assistant")
                        .put("content", contentOf(m, i in withImages)),
                )
            }
        }
        // Zdjecia czytamy z dysku poza watkiem ekranu; sam tekst jest juz w pamieci.
        if (withImages.isEmpty()) build() else withContext(Dispatchers.IO) { build() }
        val body = JSONObject().put("model", model).put("messages", arr).put("max_tokens", 2048).put("temperature", 0.7)
        val r = call("/v1/chat/completions", "POST", body, auth)
        val choice = r.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val content = message?.optString("content").orEmpty().trim()
        // Modele rozumujace pisza najpierw tok myslenia w "reasoning", a dopiero potem
        // odpowiedz w "content". Gdy zabraknie im limitu na to drugie, content zostaje
        // pusty — wtedy lepiej pokazac to, co jest, niz nic. Chyba ze model rozwaza
        // w nim, na czym dziala: tego aplikacja nie pokazuje.
        val reasoning = message?.optString("reasoning").orEmpty().trim()
        val text = when {
            content.isNotBlank() -> content
            reasoning.isNotBlank() && Persona.showable(reasoning) -> reasoning
            reasoning.isNotBlank() -> "Model nie zdążył dokończyć odpowiedzi. Zadaj pytanie jeszcze raz."
            else -> "Model nie zwrócił odpowiedzi."
        }
        val u = r.optJSONObject("usage")
        return Reply(
            text = Persona.clean(text, name),
            inTokens = u?.optInt("prompt_tokens", 0) ?: 0,
            outTokens = u?.optInt("completion_tokens", 0) ?: 0,
        )
    }

    /** Najwyzej tyle ostatnich wiadomosci z obrazami idzie z samymi obrazami. */
    private const val IMAGE_MESSAGES = 3

    /** Najwyzej tyle obrazow w jednym zapytaniu — reszta jako opis. */
    private const val MAX_IMAGES = 8

    /**
     * Ktore wiadomosci (indeksy w [fresh]) ida z obrazami. Starsze tylko z opisem — kazdy
     * obraz wysylany jest przy kazdym pytaniu od nowa i liczy sie jako tokeny.
     */
    internal fun imageMessages(fresh: List<ChatMessage>): Set<Int> {
        val out = HashSet<Int>()
        var images = 0
        for (i in fresh.indices.reversed()) {
            val m = fresh[i]
            if (!m.fromUser) continue
            val n = m.attachments.filter { it.seenAsImage }.sumOf { it.files.size }
            if (n == 0) continue
            if (out.size >= IMAGE_MESSAGES || images + n > MAX_IMAGES) break
            out += i
            images += n
        }
        return out
    }

    /**
     * Tresc wiadomosci dla modelu. Pliki tekstowe ida jako tekst, zdjecia i strony PDF-a jako
     * obrazy (format „image_url” z adresem data:), a obraz stworzony przez model jako opis.
     */
    internal fun contentOf(m: ChatMessage, withImages: Boolean): Any {
        val text = buildString {
            append(m.text)
            for (a in m.attachments) {
                when {
                    a.kind == Attachment.Kind.Text -> {
                        if (isNotEmpty()) append("\n\n")
                        append("Plik „").append(a.name).append("”")
                        if (a.truncated) append(" (obcięty — to tylko początek)")
                        append(":\n```\n").append(a.text).append("\n```")
                    }
                    a.kind == Attachment.Kind.Pdf && withImages -> {
                        if (isNotEmpty()) append("\n\n")
                        append("PDF „").append(a.name).append("” — stron: ").append(a.pages)
                        if (a.pages > a.files.size) append(", poniżej pierwsze ").append(a.files.size)
                        append(".")
                    }
                    !withImages || a.kind == Attachment.Kind.Generated -> {
                        if (isNotEmpty()) append("\n\n")
                        append("[").append(Attachments.describe(a)).append("]")
                    }
                }
            }
        }
        if (!withImages || !m.fromUser || m.attachments.none { it.seenAsImage }) return text
        val parts = org.json.JSONArray()
        parts.put(JSONObject().put("type", "text").put("text", text.ifBlank { "Co jest na obrazie?" }))
        for (a in m.attachments.filter { it.seenAsImage }) {
            for (f in a.files) {
                val url = Attachments.dataUrl(app, f, if (a.kind == Attachment.Kind.Pdf) "image/jpeg" else a.mime) ?: continue
                parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
            }
        }
        return parts
    }

    /**
     * Zwija starsza czesc rozmowy w notatke i zwraca nowa pamiec. Kosztuje jedno
     * dodatkowe zapytanie, ale zwraca sie po kilku kolejnych wiadomosciach, bo
     * przestajemy slac caly zapis. Gdy sie nie uda, zwracamy pamiec bez zmian —
     * rozmowa dziala dalej, tyle ze drozej.
     */
    suspend fun fold(model: String, messages: List<ChatMessage>, memory: Memory, auth: String? = token): Memory {
        val fresh = freshOf(messages, memory)
        val toFold = fresh.dropLast(KEEP_VERBATIM)
        if (toFold.isEmpty()) return memory

        val transcript = toFold.joinToString("\n") { m ->
            (if (m.fromUser) "Użytkownik: " else "Asystent: ") + m.text +
                m.attachments.joinToString("") { a ->
                    " [" + Attachments.describe(a) + "]" +
                        (if (a.kind == Attachment.Kind.Text) " " + a.text.take(2000) else "")
                }
        }
        val instruction = buildString {
            append("Zbierz z poniższego fragmentu rozmowy wyłącznie to, co przyda się dalej:\n")
            append("— ustalone fakty i potwierdzone wyniki,\n")
            append("— podjęte decyzje,\n")
            append("— podejścia, które zawiodły, wraz z powodem, żeby ich nie powtarzać,\n")
            append("— wątki otwarte, czekające na dokończenie.\n\n")
            append("Pomiń uprzejmości, powtórzenia i dygresje bez wniosku. ")
            append("Pisz zwięźle, w punktach, najwyżej 200 słów. Nie dopisuj niczego od siebie.\n\n")
            if (memory.summary.isNotBlank()) {
                append("Dotychczasowe ustalenia (scal je z nowym materiałem, nic nie gubiąc):\n")
                append(memory.summary)
                append("\n\n")
            }
            append("Fragment rozmowy:\n")
            append(transcript)
        }

        val arr = org.json.JSONArray()
        arr.put(JSONObject().put("role", "user").put("content", instruction))
        val body = JSONObject().put("model", model).put("messages", arr)
            .put("max_tokens", 400).put("temperature", 0.2)

        return try {
            val r = call("/v1/chat/completions", "POST", body, auth)
            val text = r.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty().trim()
            if (text.isBlank()) memory
            else Memory(summary = text, folded = memory.folded + toFold.size)
        } catch (e: Exception) {
            memory
        }
    }

    suspend fun logout(ctx: Context) {
        try { call("/logout", "POST") } catch (_: Exception) {}
        saveToken(ctx, null)
    }

    /** Uniewaznia na serwerze konkretny token, nie ruszajac biezacej sesji. */
    suspend fun revoke(sessionToken: String) {
        try { call("/logout", "POST", auth = sessionToken) } catch (_: Exception) {}
    }

    /**
     * Dzienny limit obrazow na koncie. Null, gdy serwer nie umie tworzyc obrazow
     * (modul obrazy.js nie jest wgrany) — wtedy aplikacja nie proponuje obrazow.
     */
    suspend fun imageQuota(auth: String? = token): ImageQuota? = try {
        quotaOf(call("/v1/images/quota", auth = auth))
    } catch (e: ApiError) {
        if (e.status == 404) null else throw e
    }

    private fun quotaOf(o: JSONObject) = ImageQuota(
        limit = o.optInt("limit"),
        used = o.optInt("used"),
        left = o.optInt("left"),
    )

    /** Obraz stworzony przez serwer CYPHR razem z nowym stanem limitu. */
    class Image(val bytes: ByteArray, val mime: String, val quota: ImageQuota)

    /** Tworzy obraz. [aspect]: "1:1", "16:9" albo "9:16". Limit sprawdza serwer. */
    suspend fun generateImage(prompt: String, aspect: String, auth: String? = token): Image {
        val r = call(
            "/v1/images/generations",
            "POST",
            JSONObject().put("prompt", prompt).put("aspect", aspect),
            auth,
        )
        val first = r.optJSONArray("images")?.optJSONObject(0)
            ?: throw ApiError("Serwer nie oddał obrazu. Spróbuj ponownie.", "image_failed")
        val bytes = try {
            android.util.Base64.decode(first.optString("b64"), android.util.Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw ApiError("Serwer oddał uszkodzony obraz. Spróbuj ponownie.", "image_failed")
        }
        if (bytes.isEmpty()) throw ApiError("Serwer nie oddał obrazu. Spróbuj ponownie.", "image_failed")
        return Image(bytes, first.optString("mime").ifBlank { "image/png" }, quotaOf(r))
    }
}
