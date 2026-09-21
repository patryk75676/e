package pl.cyphr.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiError(message: String, val code: String? = null, val status: Int = 0) : Exception(message)

data class User(
    val id: Long,
    val email: String,
    val name: String,
    val picture: String?,
    val balanceUsd: Double,
)

/**
 * Modele oferowane w aplikacji. Trzymamy je tutaj, bo /v1/models zwraca caly cennik
 * dostawcy albo blad, a klientowi pokazujemy wybrana liste. Rozmowa dziala niezaleznie
 * od tego katalogu — backend przekazuje do dostawcy identyfikator, ktory dostanie.
 */
val CATALOG = listOf(
    // Nazwy jeden do jednego z katalogiem dostawcy. Skrocone myla sie z wersjami
    // ocenzurowanymi, ktore nazywaja sie tak samo bez dopisku.
    Agent(
        "qwen3.8-27b-uncensored",
        "Qwen3.8 27B Uncensored",
        "131 tys. kontekstu",
    ),
    Agent(
        "glm-5.3-flash-uncensored",
        "GLM 5.3 Flash Uncensored",
        "262 tys. kontekstu",
    ),
)

data class Pack(val id: String, val name: String, val amountUsd: Double)
data class Agent(val id: String, val name: String, val description: String)
data class ChatMessage(val text: String, val fromUser: Boolean)
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
    return older.sumOf { estimateTokens(it.text) } > FOLD_ABOVE
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

    fun load(ctx: Context) {
        token = SecureStore.get(KEY_TOKEN)
    }

    /** Adres serwera. Mozna go zmienic w ustawieniach aplikacji. */
    private fun base(): String = Prefs.apiUrl

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

    private suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(base() + path)
            token?.let { builder.header("Authorization", "Bearer $it") }
            when (method) {
                "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(json))
                else -> builder.get()
            }
            val response = try {
                client.newCall(builder.build()).execute()
            } catch (e: IOException) {
                throw ApiError("Brak połączenia z internetem.", "offline")
            }
            response.use {
                val text = it.body?.string().orEmpty()
                val data = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (!it.isSuccessful) {
                    // Limiter i awarie serwera odpowiadaja zwyklym tekstem, nie JSON-em.
                    // Bez tego uzytkownik widzialby tylko "Cos poszlo nie tak".
                    val fromServer = data.optString("message").ifBlank { null }
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

    /** Cena po polsku: przecinek, dwie cyfry. */
    private fun money(v: Double): String = String.format(java.util.Locale("pl"), "%.2f", v)

    /** Zrozumiala wiadomosc dla bledu, przy ktorym serwer nie przyslal swojej. */
    internal fun explain(status: Int, retryAfter: Int?): String = when (status) {
        401 -> "Sesja wygasła. Zaloguj się ponownie."
        403 -> "Brak dostępu do tej funkcji."
        404 -> "Serwer nie zna tej funkcji. Zaktualizuj aplikację."
        408, 504 -> "Serwer nie odpowiedział na czas. Spróbuj jeszcze raz."
        429 -> {
            val za = retryAfter?.let { s -> if (s >= 60) "${s / 60} min" else "$s s" }
            if (za != null) "Za dużo prób pod rząd. Odczekaj $za i spróbuj ponownie."
            else "Za dużo prób pod rząd. Odczekaj chwilę i spróbuj ponownie."
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

    suspend fun me(): User = user(call("/me").getJSONObject("user")).also { cacheUser(it) }

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


    /** Lista modeli z proxy Routeway. Sluzy jako lista agentow do wyboru. */
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
            Agent(id, o.optString("name").ifBlank { id }, price ?: o.optString("owned_by"))
        }
    }

    /** Pojedyncza odpowiedz czatu. Historia jest wysylana w calosci. */
    suspend fun chat(
        model: String,
        messages: List<ChatMessage>,
        memory: Memory = Memory(),
        toolInstructions: String? = null,
    ): Reply {
        val arr = org.json.JSONArray()
        val system = listOfNotNull(Prefs.systemPrompt.takeIf { it.isNotBlank() }, toolInstructions)
            .joinToString("\n")
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
        freshOf(messages, memory).forEach { m ->
            arr.put(JSONObject().put("role", if (m.fromUser) "user" else "assistant").put("content", m.text))
        }
        val body = JSONObject().put("model", model).put("messages", arr).put("max_tokens", 2048).put("temperature", 0.7)
        val r = call("/v1/chat/completions", "POST", body)
        val choice = r.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val content = message?.optString("content").orEmpty().trim()
        // Modele rozumujace pisza najpierw tok myslenia w "reasoning", a dopiero potem
        // odpowiedz w "content". Gdy zabraknie im limitu na to drugie, content zostaje
        // pusty — wtedy lepiej pokazac to, co jest, niz nic.
        val reasoning = message?.optString("reasoning").orEmpty().trim()
        val u = r.optJSONObject("usage")
        return Reply(
            text = content.ifBlank { reasoning }.ifBlank { "Model nie zwrócił odpowiedzi." },
            inTokens = u?.optInt("prompt_tokens", 0) ?: 0,
            outTokens = u?.optInt("completion_tokens", 0) ?: 0,
        )
    }

    /**
     * Zwija starsza czesc rozmowy w notatke i zwraca nowa pamiec. Kosztuje jedno
     * dodatkowe zapytanie, ale zwraca sie po kilku kolejnych wiadomosciach, bo
     * przestajemy slac caly zapis. Gdy sie nie uda, zwracamy pamiec bez zmian —
     * rozmowa dziala dalej, tyle ze drozej.
     */
    suspend fun fold(model: String, messages: List<ChatMessage>, memory: Memory): Memory {
        val fresh = freshOf(messages, memory)
        val toFold = fresh.dropLast(KEEP_VERBATIM)
        if (toFold.isEmpty()) return memory

        val transcript = toFold.joinToString("\n") {
            (if (it.fromUser) "Użytkownik: " else "Asystent: ") + it.text
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
            val r = call("/v1/chat/completions", "POST", body)
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
}
