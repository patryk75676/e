package pl.cyphr.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Szyfrowany schowek na token sesji i dane SSH.
 * Klucz siedzi w Keystore urzadzenia, wiec nic nie leży w plikach jawnym tekstem.
 */
object SecureStore {
    private const val FILE = "cyphr_secure"
    private var prefs: SharedPreferences? = null

    /**
     * Zapas na wypadek odmowy Keystore. Ginie razem z procesem — i o to chodzi.
     * Token sesji ani haslo SSH nie moga trafic na dysk bez szyfrowania, wiec
     * gdy szyfrowany schowek nie wstaje, zostaja tylko w pamieci na ta sesje.
     */
    private val memory = mutableMapOf<String, String>()

    /** Falsz oznacza, ze sekrety zyja tylko do zamkniecia aplikacji. */
    var encrypted = false
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        prefs = try {
            val key = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { encrypted = true }
        } catch (e: Exception) {
            encrypted = false
            null
        }
    }

    fun get(key: String): String? = prefs?.getString(key, null) ?: memory[key]

    fun put(key: String, value: String?) {
        val store = prefs
        if (store != null) {
            store.edit().apply { if (value.isNullOrBlank()) remove(key) else putString(key, value) }.apply()
        } else {
            if (value.isNullOrBlank()) memory.remove(key) else memory[key] = value
        }
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
        memory.clear()
    }
}
