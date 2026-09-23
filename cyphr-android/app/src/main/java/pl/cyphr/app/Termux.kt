package pl.cyphr.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Most do zainstalowanego Termuxa. Termux nie da sie wbudowac w cudzy APK —
 * jego srodowisko siedzi w prywatnym katalogu wlasnej paczki, a piaskownica
 * Androida nie pozwala tam zajrzec. Zamiast tego zlecamy mu polecenia przez
 * jego wlasne API RUN_COMMAND i odbieramy wynik.
 */
object Termux {
    const val PACKAGE = "com.termux"
    const val PLAY_URL = "https://f-droid.org/packages/com.termux/"

    /** Termux oznacza to uprawnienie jako niebezpieczne — trzeba o nie spytac w czasie dzialania. */
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"

    data class Result(val stdout: String, val stderr: String, val exitCode: Int)

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun storeIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_URL))

    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)

    /** Polecenie, ktore uzytkownik wkleja w Termuxie, zeby wpuscil polecenia z zewnatrz. */
    const val SETUP_COMMAND =
        "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings"

    enum class State { NotInstalled, NoPermission, Ready }

    /** Ostatni wynik sprawdzenia i jego czas — patrz [check]. */
    @Volatile private var lastCheck: Pair<Long, State>? = null

    /**
     * Sprawdza realnie, wykonujac polecenie — sama obecnosc paczki nic nie mowi o zgodzie.
     *
     * Wynik jest pamietany przez [maxAgeMs], bo czat pyta o to przy kazdej wiadomosci.
     * Termux bez zgody na polecenia z zewnatrz potrafi w ogole nie odpowiedziec —
     * dlatego krotki limit czasu; bez niego czat wisial na zawsze.
     */
    suspend fun check(context: Context, maxAgeMs: Long = 60_000): State {
        if (!isInstalled(context)) return State.NotInstalled.also { lastCheck = null }
        lastCheck?.let { (at, state) ->
            if (System.currentTimeMillis() - at < maxAgeMs) return state
        }
        val r = run(context, "echo CYPHR_OK", timeoutMs = 4_000)
        val state = if (r.stdout.contains("CYPHR_OK")) State.Ready else State.NoPermission
        lastCheck = System.currentTimeMillis() to state
        return state
    }

    /**
     * Uruchamia polecenie w Termuxie i czeka na wynik najwyzej [timeoutMs].
     * Po czasie przestajemy czekac — samo polecenie moze dalej dzialac w Termuxie,
     * ale aplikacja nie stoi.
     */
    suspend fun run(context: Context, command: String, timeoutMs: Long = 60_000): Result =
        withTimeoutOrNull(timeoutMs) { runUnbounded(context, command) }
            ?: Result(
                stdout = "",
                stderr = "Termux nie odpowiedział w ${timeoutMs / 1000} s. Jeśli polecenia w ogóle " +
                    "nie wracają, wpisz 'termux' w terminalu — wypiszę, jak to ustawić.",
                exitCode = -1,
            )

    /**
     * Termux odsyla wynik przez PendingIntent, wiec na czas wywolania rejestrujemy
     * odbiornik pod losowa akcja. Anulowanie (np. limit czasu) wyrejestrowuje go.
     */
    private suspend fun runUnbounded(context: Context, command: String): Result =
        suspendCancellableCoroutine { cont ->
            val replyAction = "pl.cyphr.app.TERMUX_REPLY." + System.nanoTime()

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    val bundle = intent.getBundleExtra("result")
                    if (cont.isActive) {
                        cont.resume(
                            Result(
                                stdout = bundle?.getString("stdout").orEmpty(),
                                stderr = bundle?.getString("stderr").orEmpty(),
                                exitCode = bundle?.getInt("exitCode", -1) ?: -1,
                            ),
                        )
                    }
                }
            }

            val filter = IntentFilter(replyAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

            // Termux dopisuje wynik do tego intentu, wiec musi byc zmienialny.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val reply = PendingIntent.getBroadcast(
                context,
                replyAction.hashCode(),
                Intent(replyAction).setPackage(context.packageName),
                flags,
            )

            val intent = Intent().apply {
                setClassName(PACKAGE, SERVICE)
                action = ACTION
                putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", reply)
            }

            try {
                context.startService(intent)
            } catch (e: Exception) {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                if (cont.isActive) {
                    cont.resume(
                        Result(
                            stdout = "",
                            stderr = "Termux odrzucił polecenie: ${e.message}\n" +
                                "Sprawdź, czy w ~/.termux/termux.properties jest allow-external-apps=true",
                            exitCode = -1,
                        ),
                    )
                }
            }

            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }
}
