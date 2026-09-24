package pl.cyphr.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Trzyma proces przy zyciu, poki model odpowiada — takze gdy uzytkownik wyjdzie
 * z aplikacji albo ja zamknie. Sama praca siedzi w [ChatEngine]; usluga tylko
 * pilnuje, zeby Android jej nie ubil, i znika, gdy nic nie jest w toku.
 */
class ChatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    /** Numer ostatniego zlecenia, ktore dotarlo do uslugi. */
    private val started = MutableStateFlow(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME) ?: Persona.BRAND
        ServiceCompat.startForeground(
            this,
            Notifications.WORKING_ID,
            Notifications.working(this, name),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        started.value = startId
        // Konczymy sie sami, dopiero po wejsciu na pierwszy plan. Zatrzymanie przed
        // startForeground Android karze zamknieciem aplikacji.
        if (watcher == null) {
            watcher = scope.launch {
                while (true) {
                    ChatEngine.busy.first { it.isEmpty() }
                    val id = started.value
                    // Tylko gdy od tamtej pory nie przyszlo nowe zlecenie. Zwykle stopSelf()
                    // zatrzymalby tez to, ktore jest juz w drodze, a jego onStartCommand nie
                    // zdazylby wejsc na pierwszy plan — Android zamyka wtedy aplikacje.
                    if (stopSelfResult(id)) return@launch
                    // Nowe zlecenie w drodze: czekamy na nie i sprawdzamy od nowa.
                    started.first { it != id }
                }
            }
        }
        // Po ubiciu procesu nie ma czego wznawiac — odpowiedz zapisala sie albo przepadla z nim.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_NAME = "name"

        fun start(context: Context, name: String) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ChatService::class.java).putExtra(EXTRA_NAME, name),
                )
            } catch (e: Exception) {
                // Np. start z tla zabroniony przez system — odpowiedz i tak idzie, poki proces zyje.
            }
        }
    }
}
