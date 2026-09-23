package pl.cyphr.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Pojedyncza rozmowa. [memory] to zwinieta czesc historii — patrz Api.fold.
 * Tytul powstaje z pierwszej wiadomosci, chyba ze uzytkownik nada wlasny.
 */
data class Chat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val memory: Memory = Memory(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val label: String
        get() = title.ifBlank {
            messages.firstOrNull { it.fromUser }?.text
                ?.replace('\n', ' ')?.trim()?.take(40)
                ?.ifBlank { null }
                ?: "Nowa rozmowa"
        }
}

/**
 * Podmienia rozmowe [id], liczac od jej AKTUALNEJ postaci na tej liscie.
 *
 * Odpowiedz modelu przychodzi po kilku sekundach, a do tego czasu rozmowa zdazyla
 * sie zmienic — choćby o samo pytanie. Liczenie od kopii z chwili wyslania dopisywalo
 * odpowiedz do stanu sprzed pytania i pytanie znikalo. Zwraca nowa liste razem
 * ze zmieniona rozmowa albo null, gdy rozmowy juz nie ma (usunieta w trakcie albo
 * przelaczono konto) — wtedy niczego nie wskrzeszamy.
 */
fun List<Chat>.updated(id: String, now: Long, block: (Chat) -> Chat): Pair<List<Chat>, Chat>? {
    val current = firstOrNull { it.id == id } ?: return null
    val next = block(current).copy(updatedAt = now)
    return map { if (it.id == id) next else it } to next
}

/**
 * Rozmowa bez wiadomosci [index]. Gdy wiadomosc byla juz zwinieta w notatke pamieci,
 * licznik zwinietych maleje o jeden — inaczej pierwsza swieza wiadomosc wypadlaby
 * z tego, co leci do modelu.
 */
fun Chat.withoutMessage(index: Int): Chat {
    if (index !in messages.indices) return this
    val folded = if (index < memory.folded) memory.folded - 1 else memory.folded
    return copy(messages = messages.filterIndexed { i, _ -> i != index }, memory = memory.copy(folded = folded))
}

/**
 * Rozmowy leza w osobnych plikach, wiec dopisanie wiadomosci do jednej nie
 * przepisuje pozostalych. Kazde konto ma wlasny podkatalog — wylogowanie ich
 * nie kasuje, a inne konto na tym samym telefonie ich nie zobaczy.
 * Calosc siedzi w prywatnym katalogu aplikacji, wiec obce aplikacje nie czytaja.
 */
object Chats {
    private const val DIR = "chats"
    private const val MAX_MESSAGES = 400

    private fun dir(context: Context, userId: Long) =
        File(File(context.filesDir, DIR), userId.toString()).apply { mkdirs() }

    private fun file(context: Context, userId: Long, id: String) = File(dir(context, userId), "$id.json")

    fun loadAll(context: Context, userId: Long): List<Chat> = try {
        dir(context, userId).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Jedna rozmowa albo null, gdy jej nie ma (np. usunieta w trakcie odpowiedzi). */
    fun load(context: Context, userId: Long, id: String): Chat? =
        file(context, userId, id).takeIf { it.exists() }?.let { read(it) }

    private fun read(f: File): Chat? = try {
        val root = JSONObject(f.readText())
        val arr = root.optJSONArray("messages") ?: JSONArray()
        Chat(
            id = root.optString("id").ifBlank { f.nameWithoutExtension },
            title = root.optString("title"),
            messages = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val att = o.optJSONArray("att")
                ChatMessage(
                    o.optString("text"),
                    o.optBoolean("user"),
                    att?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(Attachment::fromJson) } }
                        .orEmpty(),
                )
            },
            memory = Memory(root.optString("summary"), root.optInt("folded", 0)),
            updatedAt = root.optLong("updatedAt", 0L),
        )
    } catch (e: Exception) {
        null
    }

    /**
     * Zapis idzie najpierw do pliku tymczasowego, a potem podmienia wlasciwy. Przy
     * odpowiedzi z poleceniami kilka zapisow tej samej rozmowy leci tuz po sobie —
     * rownolegle writeText do jednego pliku potrafilo go przemieszac, a przemieszany
     * plik przy wczytaniu po cichu wypadal razem z cala rozmowa. Stad tez jeden zapis naraz.
     */
    @Synchronized
    fun save(context: Context, userId: Long, chat: Chat) {
        try {
            // Przyciecie najstarszych wiadomosci przesuwa indeksy, wiec licznik
            // zwinietych musi isc za nim — inaczej notatka zaslonilaby wiadomosci,
            // ktorych nie obejmuje.
            val dropped = (chat.messages.size - MAX_MESSAGES).coerceAtLeast(0)
            val arr = JSONArray()
            chat.messages.takeLast(MAX_MESSAGES).forEach { m ->
                val o = JSONObject().put("text", m.text).put("user", m.fromUser)
                if (m.attachments.isNotEmpty()) o.put("att", JSONArray(m.attachments.map { it.toJson() }))
                arr.put(o)
            }
            val root = JSONObject()
                .put("id", chat.id)
                .put("title", chat.title)
                .put("messages", arr)
                .put("summary", chat.memory.summary)
                .put("folded", (chat.memory.folded - dropped).coerceAtLeast(0))
                .put("updatedAt", chat.updatedAt)
            val target = file(context, userId, chat.id)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                // Na czesci systemow plikow rename nie nadpisuje istniejacego pliku.
                target.delete()
                if (!tmp.renameTo(target)) tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun delete(context: Context, userId: Long, id: String) {
        try { file(context, userId, id).delete() } catch (_: Exception) {}
    }
}
