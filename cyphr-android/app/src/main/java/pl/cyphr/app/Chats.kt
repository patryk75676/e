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

    private fun read(f: File): Chat? = try {
        val root = JSONObject(f.readText())
        val arr = root.optJSONArray("messages") ?: JSONArray()
        Chat(
            id = root.optString("id").ifBlank { f.nameWithoutExtension },
            title = root.optString("title"),
            messages = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                ChatMessage(o.optString("text"), o.optBoolean("user"))
            },
            memory = Memory(root.optString("summary"), root.optInt("folded", 0)),
            updatedAt = root.optLong("updatedAt", 0L),
        )
    } catch (e: Exception) {
        null
    }

    fun save(context: Context, userId: Long, chat: Chat) {
        try {
            // Przyciecie najstarszych wiadomosci przesuwa indeksy, wiec licznik
            // zwinietych musi isc za nim — inaczej notatka zaslonilaby wiadomosci,
            // ktorych nie obejmuje.
            val dropped = (chat.messages.size - MAX_MESSAGES).coerceAtLeast(0)
            val arr = JSONArray()
            chat.messages.takeLast(MAX_MESSAGES).forEach {
                arr.put(JSONObject().put("text", it.text).put("user", it.fromUser))
            }
            val root = JSONObject()
                .put("id", chat.id)
                .put("title", chat.title)
                .put("messages", arr)
                .put("summary", chat.memory.summary)
                .put("folded", (chat.memory.folded - dropped).coerceAtLeast(0))
                .put("updatedAt", chat.updatedAt)
            file(context, userId, chat.id).writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    fun delete(context: Context, userId: Long, id: String) {
        try { file(context, userId, id).delete() } catch (_: Exception) {}
    }
}
