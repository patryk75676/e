package pl.cyphr.app

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Zgoda na sterowanie Termuksem prosi sie sama — w chwili, gdy polecenie ma do niego
 * isc (terminal w trybie Termux albo polecenie modelu). Nie trzeba jej szukac w
 * Ustawieniach. Okno pokazuje system Androida, bo Termux oznacza swoje uprawnienie
 * jako niebezpieczne i sama deklaracja w manifescie nie wystarcza.
 */
object TermuxPermission {
    private var launcher: ActivityResultLauncher<String>? = null
    private var waiting: CompletableDeferred<Boolean>? = null

    /** Rejestruje okno zgody. Wolane w onCreate aktywnosci — przed jej startem. */
    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            waiting?.complete(granted)
            waiting = null
        }
    }

    /**
     * Czy polecenia moga isc do Termuksa — jesli zgody brak, system pyta teraz.
     * Bez zainstalowanego Termuksa nie ma o co pytac. Gdy zgode odrzucono na stale,
     * system nie pokazuje okna i od razu wraca odmowa; wtedy zostaje
     * Ustawienia → Terminal, gdzie jest droga do ustawien aplikacji.
     */
    suspend fun ensure(context: Context): Boolean {
        if (!Termux.isInstalled(context)) return false
        if (Termux.hasPermission(context)) return true
        val ask = launcher ?: return false
        val answer = CompletableDeferred<Boolean>()
        // Drugie pytanie w trakcie pierwszego — pierwsze konczy sie odmowa, nie wisi.
        waiting?.complete(false)
        waiting = answer
        withContext(Dispatchers.Main) { ask.launch(Termux.PERMISSION) }
        val granted = answer.await()
        // Zapamietany stan „brak zgody” jest juz nieaktualny.
        if (granted) Termux.forget()
        return granted
    }
}
