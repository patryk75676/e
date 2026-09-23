package pl.cyphr.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pytanie o uprawnienie w chwili, gdy jest potrzebne — nie trzeba go szukac w Ustawieniach.
 * Okno pokazuje system Androida; [ask] czeka na odpowiedz. Rejestracja musi byc w onCreate
 * aktywnosci, przed jej startem.
 */
class PermissionAsker(private val permission: String) {
    private companion object {
        /** Okno systemu zawsze odpowiada, ale praca w tle nie moze na nie czekac w nieskonczonosc. */
        const val TIMEOUT_MS = 5 * 60 * 1000L
    }

    private var launcher: ActivityResultLauncher<String>? = null
    private var waiting: CompletableDeferred<Boolean>? = null

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            waiting?.complete(granted)
            waiting = null
        }
    }

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Czy uprawnienie jest — jesli nie, system pyta teraz. Gdy odrzucono je na stale,
     * system nie pokazuje okna i od razu wraca odmowa. Bez zywego ekranu (aplikacja
     * zamknieta) nie ma kogo zapytac — wtedy tez odmowa, a nie wyjatek.
     */
    suspend fun ask(context: Context): Boolean {
        if (granted(context)) return true
        val request = launcher ?: return false
        val answer = CompletableDeferred<Boolean>()
        // Drugie pytanie w trakcie pierwszego — pierwsze konczy sie odmowa, nie wisi.
        waiting?.complete(false)
        waiting = answer
        val launched = withContext(Dispatchers.Main) {
            try { request.launch(permission); true } catch (e: Exception) { false }
        }
        if (!launched) { waiting = null; return false }
        return withTimeoutOrNull(TIMEOUT_MS) { answer.await() } ?: false
    }
}

/**
 * Zgoda na sterowanie Termuksem prosi sie sama — w chwili, gdy polecenie ma do niego
 * isc (terminal w trybie Termux albo polecenie modelu). Termux oznacza swoje uprawnienie
 * jako niebezpieczne, wiec sama deklaracja w manifescie nie wystarcza.
 */
object TermuxPermission {
    private val asker = PermissionAsker(Termux.PERMISSION)

    fun register(activity: ComponentActivity) = asker.register(activity)

    /**
     * Czy polecenia moga isc do Termuksa. Bez zainstalowanego Termuksa nie ma o co pytac.
     * Po odmowie na stale zostaje Ustawienia → Terminal z droga do ustawien aplikacji.
     */
    suspend fun ensure(context: Context): Boolean {
        if (!Termux.isInstalled(context)) return false
        if (Termux.hasPermission(context)) return true
        val granted = asker.ask(context)
        // Zapamietany stan „brak zgody” jest juz nieaktualny.
        if (granted) Termux.forget()
        return granted
    }
}

/**
 * Powiadomienia (Android 13+ pyta o nie osobno): gotowa odpowiedz, gdy aplikacja jest
 * w tle, albo prosba modelu o zgode na polecenie. Pytamy raz, przy pierwszej wiadomosci —
 * odmowy nie ponawiamy, czat dziala i bez powiadomien.
 */
object NotificationPermission {
    private val asker = PermissionAsker(Manifest.permission.POST_NOTIFICATIONS)

    fun register(activity: ComponentActivity) = asker.register(activity)

    suspend fun askOnce(context: Context) {
        if (Build.VERSION.SDK_INT < 33 || asker.granted(context) || Prefs.notificationsAsked) return
        Prefs.setNotificationsAsked()
        asker.ask(context)
    }
}
