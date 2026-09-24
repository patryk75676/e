package pl.cyphr.app

import org.json.JSONArray
import org.json.JSONObject

/** Konto zapamietane na tym telefonie razem z jego tokenem sesji. */
data class Account(
    val id: Long,
    val email: String,
    val name: String,
    val token: String,
)

/**
 * Konta zapamietane na urzadzeniu. Odcisk palca odblokowuje telefon, a potem
 * uzytkownik wybiera, na ktore konto wejsc — biometria nie potwierdza tozsamosci
 * serwerowi, tylko chroni dostep do zapisanych tokenow. Cala lista siedzi
 * w szyfrowanym schowku, bo tokeny sa w niej jawnie.
 */
object Accounts {
    private const val KEY = "accounts"

    fun all(): List<Account> = try {
        val raw = SecureStore.get(KEY).orEmpty()
        val arr = if (raw.isBlank()) JSONArray() else JSONArray(raw)
        (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val token = o.optString("token").ifBlank { return@mapNotNull null }
            Account(
                id = o.optLong("id"),
                email = o.optString("email"),
                name = o.optString("name"),
                token = token,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(list: List<Account>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("email", it.email)
                    .put("name", it.name).put("token", it.token),
            )
        }
        SecureStore.put(KEY, if (list.isEmpty()) null else arr.toString())
    }

    /** Dodaje konto albo odswieza token juz zapamietanego. */
    fun upsert(account: Account) {
        write(all().filterNot { it.id == account.id } + account)
    }

    fun remove(id: Long) {
        write(all().filterNot { it.id == id })
    }
}
