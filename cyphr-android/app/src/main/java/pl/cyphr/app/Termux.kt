package pl.cyphr.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Most do zainstalowanego Termuxa. Termux nie da sie wbudowac w cudzy APK —
 * jego srodowisko siedzi w prywatnym katalogu wlasnej paczki, a piaskownica
 * Androida nie pozwala tam zajrzec. Zamiast tego zlecamy mu polecenia przez
 * jego wlasne API RUN_COMMAND i odbieramy wynik.
 *
 * Nazwy pol i zachowanie wg zrodel Termuxa (TermuxConstants, RunCommandService)
 * i jego dokumentacji RUN_COMMAND Intent.
 */
object Termux {
    const val PACKAGE = "com.termux"
    const val PLAY_URL = "https://f-droid.org/packages/com.termux/"

    /** Termux oznacza to uprawnienie jako niebezpieczne — trzeba o nie spytac w czasie dzialania. */
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    const val HOME = "/data/data/com.termux/files/home"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val EXTRA = "com.termux.RUN_COMMAND_"

    /** Wynik przez PendingIntent (i bledy err/errmsg) Termux odsyla dopiero od tej wersji. */
    private const val MIN_RESULTS_VERSION = 109

    /** Odpowiedz na sprawdzenie polaczenia. */
    const val MARKER = "CYPHR_OK"

    /** Activity.RESULT_OK — tak Termux oznacza brak bledu po swojej stronie. */
    private const val ERR_SUCCESS = -1

    enum class Failure {
        /** Aplikacja nie ma uprawnienia RUN_COMMAND. */
        NoPermission,
        /** Android nie pozwolil uruchomic uslugi Termuxa. */
        NotStarted,
        /** Termux odmowil: allow-external-apps nie jest ustawione na true. */
        ExternalAppsBlocked,
        /** Inny blad zgloszony przez sam Termux. */
        TermuxError,
        /** Termux nie odpowiedzial w czasie. */
        NoReply,
    }

    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        /** Komunikat bledu od Termuxa albo Androida — pokazujemy go doslownie. */
        val errMsg: String? = null,
        val failure: Failure? = null,
        /** Termux oddaje najwyzej ~100 KB wyniku, reszte ucina od poczatku. */
        val truncated: Boolean = false,
    )

    enum class State { NotInstalled, NoCommandApi, TooOld, NoPermission, ExternalAppsBlocked, NoReply, Failed, Ready }

    /** Stan polaczenia razem z tym, co Termux sam powiedzial, gdy cos nie gra. */
    data class Status(val state: State, val detail: String? = null, val version: String? = null)

    fun isInstalled(context: Context): Boolean = packageInfo(context) != null

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Czy zainstalowany Termux w ogole przyjmuje polecenia od innych aplikacji. Termux z Google Play
     * (wersje „googleplay.…”) to osobna aplikacja: nie definiuje zgody RUN_COMMAND ani uslugi, ktora
     * ja obsluguje. Wtedy nie ma o co pytac ani czego wlaczac w ustawieniach — system od razu
     * odpowiadal odmowa, a aplikacja brala to za „odrzucono na stale”.
     */
    fun acceptsCommands(context: Context): Boolean = try {
        context.packageManager.getPermissionInfo(PERMISSION, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Nazwa zgody tak, jak pokazuje ja ten telefon w ustawieniach (Termux nadaje ja sam). */
    fun permissionLabel(context: Context): String? = try {
        val pm = context.packageManager
        pm.getPermissionInfo(PERMISSION, 0).loadLabel(pm).toString().ifBlank { null }
    } catch (e: Exception) {
        null
    }

    /** Czy to Termux z Google Play — po nazwie wersji, ktora nadaje mu jego sklep. */
    fun isPlayBuild(versionName: String?): Boolean = versionName?.trim()?.startsWith("googleplay") == true

    private fun packageInfo(context: Context) = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun storeIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_URL))

    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)

    /** Ekran ustawien Termuxa — tam wylacza sie optymalizacje baterii. */
    fun termuxSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PACKAGE"))

    /** Ekran ustawien tej aplikacji — gdy zgode odrzucono na stale, tylko tam da sie ja dac. */
    fun ownSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /**
     * Wpis w ~/.termux/termux.properties. Najpierw kasujemy kazdy aktywny wpis
     * allow-external-apps (komentarze zostaja), potem dopisujemy jeden — wklejenie
     * dwa razy albo plik z `false` niczego nie psuje.
     */
    const val SETUP_PROPERTIES =
        "mkdir -p ~/.termux && touch ~/.termux/termux.properties && " +
            "sed -i '/^[[:space:]]*allow-external-apps/d' ~/.termux/termux.properties && " +
            "echo 'allow-external-apps = true' >> ~/.termux/termux.properties"

    /** Krotkie wyjasnienie dla Termuksa bez zgody RUN_COMMAND (wersja z Google Play). */
    const val NO_COMMAND_API =
        "Ten Termux (z Google Play) nie przyjmuje poleceń od innych aplikacji. Potrzebny jest Termux z F-Droid."

    /** Polecenie, ktore uzytkownik wkleja w Termuxie, zeby wpuscil polecenia z zewnatrz. */
    const val SETUP_COMMAND = "$SETUP_PROPERTIES && termux-reload-settings"

    /**
     * Czy ta wersja Termuxa odsyla wyniki. Null, gdy wersji nie da sie odczytac —
     * wtedy nie blokujemy, rozstrzyga prawdziwe polecenie.
     */
    internal fun supportsResults(versionName: String?, versionCode: Long): Boolean? {
        val match = versionName?.let { Regex("""^v?(\d+)\.(\d+)""").find(it.trim()) } ?: return null
        val (major, minor) = match.destructured
        if (major.toInt() > 0) return true
        return minor.toInt() >= MIN_RESULTS_VERSION
    }

    /** Wynik z paczki odeslanej przez Termuxa. */
    internal fun parseResult(
        stdout: String?,
        stderr: String?,
        exitCode: Int?,
        err: Int?,
        errMsg: String?,
        stdoutOriginal: String?,
        stderrOriginal: String?,
    ): Result {
        val out = stdout.orEmpty()
        val errOut = stderr.orEmpty()
        val truncated = (stdoutOriginal?.toIntOrNull()?.let { it > out.length } ?: false) ||
            (stderrOriginal?.toIntOrNull()?.let { it > errOut.length } ?: false)
        val failure = when {
            err == null || err == ERR_SUCCESS -> null
            errMsg?.contains("allow-external-apps", ignoreCase = true) == true -> Failure.ExternalAppsBlocked
            else -> Failure.TermuxError
        }
        return Result(out, errOut, exitCode ?: -1, errMsg?.takeIf { failure != null }, failure, truncated)
    }

    /**
     * Stan polaczenia, od najprostszej przyczyny do najtrudniejszej. [commandApi] — czy Termux
     * w ogole ma zgode RUN_COMMAND (Termux z Google Play jej nie ma).
     */
    internal fun diagnose(
        installed: Boolean,
        versionOk: Boolean?,
        permission: Boolean,
        probe: Result?,
        commandApi: Boolean = true,
    ): State = when {
        !installed -> State.NotInstalled
        !commandApi -> State.NoCommandApi
        versionOk == false -> State.TooOld
        !permission -> State.NoPermission
        probe == null -> State.Failed
        probe.stdout.contains(MARKER) -> State.Ready
        probe.failure == Failure.NoPermission -> State.NoPermission
        probe.failure == Failure.ExternalAppsBlocked -> State.ExternalAppsBlocked
        probe.failure == Failure.NoReply -> State.NoReply
        else -> State.Failed
    }

    /**
     * Cel zwyklego `cd` w terminalu Termuxa albo null, gdy argument ma znaki powloki.
     *
     * Termux uruchamia kazde polecenie osobno, wiec katalog pamietamy po naszej stronie.
     * Za „samo cd” uznajemy wylacznie sciezke: `cd x; rm -rf ~` musi pojsc zwykla droga,
     * z ocena ryzyka i oknem zgody — inaczej cd byloby furtka obok niego.
     */
    fun cdTarget(argument: String): String? {
        var arg = argument.trim()
        if (arg.any { it in ";&|$`<>(){}\n\\*?[]!#" }) return null
        if (arg.length >= 2 && (arg.first() == '"' || arg.first() == '\'') && arg.last() == arg.first()) {
            arg = arg.substring(1, arg.length - 1)
        }
        if (arg.contains('"') || arg.contains('\'')) return null
        return when {
            arg.isEmpty() || arg == "~" -> HOME
            arg.startsWith("~/") -> HOME + arg.removePrefix("~")
            else -> arg
        }
    }

    /** Pojedynczy argument dla basha, doslownie — nic w nim sie nie rozwija ani nie wykonuje. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Katalog domowy Termuxa jako tylda — tak jak w samym Termuksie. */
    fun shortPath(path: String): String = when {
        path == HOME -> "~"
        path.startsWith("$HOME/") -> "~" + path.removePrefix(HOME)
        else -> path
    }

    /** Ostatni wynik sprawdzenia i jego czas — patrz [check]. */
    @Volatile private var lastCheck: Pair<Long, Status>? = null

    /**
     * Sprawdza realnie, wykonujac polecenie — sama obecnosc paczki nic nie mowi o zgodzie.
     *
     * Czat pyta o to przy kazdej wiadomosci, wiec wynik jest pamietany: gotowy przez
     * minute, niegotowy dluzej — i tak trzeba cos poprawic, a przycisk w ustawieniach
     * sprawdza zawsze na swiezo (maxAgeMs = 0).
     */
    suspend fun check(context: Context, maxAgeMs: Long? = null): Status {
        val info = packageInfo(context) ?: return Status(State.NotInstalled).also { lastCheck = null }
        lastCheck?.let { (at, status) ->
            val ttl = maxAgeMs ?: if (status.state == State.Ready) 60_000L else 300_000L
            if (System.currentTimeMillis() - at < ttl) return status
        }
        val versionOk = installedSupportsResults(context)
        val api = acceptsCommands(context)
        val permission = hasPermission(context)
        val probe = if (api && versionOk != false && permission) run(context, "echo $MARKER", timeoutMs = 8_000) else null
        val state = diagnose(true, versionOk, permission, probe, commandApi = api)
        val detail = probe?.errMsg ?: probe?.stderr?.takeIf { state == State.Failed && it.isNotBlank() }
        return Status(state, detail, info.versionName).also { lastCheck = System.currentTimeMillis() to it }
    }

    /** Czy polecenia moga teraz isc do Termuxa. */
    suspend fun ready(context: Context): Boolean = check(context).state == State.Ready

    /** Zapomina zapamietany stan — np. po udzieleniu zgody, zeby nastepne sprawdzenie bylo swieze. */
    fun forget() { lastCheck = null }

    /**
     * Uruchamia polecenie w Termuxie w katalogu [workdir] i czeka na wynik najwyzej
     * [timeoutMs]. Po czasie przestajemy czekac — samo polecenie moze dalej dzialac
     * w Termuxie, ale aplikacja nie stoi.
     */
    suspend fun run(context: Context, command: String, workdir: String = HOME, timeoutMs: Long = 60_000): Result {
        if (isInstalled(context) && !acceptsCommands(context)) {
            return Result(
                stdout = "",
                stderr = "",
                exitCode = -1,
                errMsg = NO_COMMAND_API,
                failure = Failure.TermuxError,
            )
        }
        // Stara wersja (zwykle z Google Play) i tak nie odesle wyniku — nie ma na co czekac.
        if (installedSupportsResults(context) == false) {
            return Result(
                stdout = "",
                stderr = "",
                exitCode = -1,
                errMsg = "Ta wersja Termuksa jest za stara i nie odsyła wyników. Potrzebna 0.109 lub nowsza z F-Droid.",
                failure = Failure.TermuxError,
            )
        }
        return withTimeoutOrNull(timeoutMs) { runUnbounded(context, command, workdir) }
            ?: Result(
                stdout = "",
                stderr = "",
                exitCode = -1,
                errMsg = "Termux nie odpowiedział w ${timeoutMs / 1000} s.",
                failure = Failure.NoReply,
            )
    }

    /** Czy zainstalowany Termux odsyla wyniki; null, gdy nie da sie tego odczytac. */
    private fun installedSupportsResults(context: Context): Boolean? {
        val info = packageInfo(context) ?: return null
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return supportsResults(info.versionName, code)
    }

    /**
     * Termux odsyla wynik przez PendingIntent, wiec na czas wywolania rejestrujemy
     * odbiornik pod losowa akcja. Anulowanie (np. limit czasu) wyrejestrowuje go.
     */
    private suspend fun runUnbounded(context: Context, command: String, workdir: String): Result =
        suspendCancellableCoroutine { cont ->
            val replyAction = "pl.cyphr.app.TERMUX_REPLY." + System.nanoTime()

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    val b = intent.getBundleExtra("result")
                    if (cont.isActive) {
                        cont.resume(
                            parseResult(
                                stdout = b?.getString("stdout"),
                                stderr = b?.getString("stderr"),
                                exitCode = b?.takeIf { it.containsKey("exitCode") }?.getInt("exitCode"),
                                err = b?.takeIf { it.containsKey("err") }?.getInt("err"),
                                errMsg = b?.getString("errmsg"),
                                stdoutOriginal = b?.getString("stdout_original_length"),
                                stderrOriginal = b?.getString("stderr_original_length"),
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
                putExtra(EXTRA + "PATH", BASH)
                putExtra(EXTRA + "ARGUMENTS", arrayOf("-c", command))
                putExtra(EXTRA + "WORKDIR", workdir)
                putExtra(EXTRA + "BACKGROUND", true)
                putExtra(EXTRA + "PENDING_INTENT", reply)
                // Widac w powiadomieniu i logach Termuxa, kto i co uruchomil.
                putExtra(EXTRA + "COMMAND_LABEL", "CYPHR")
                putExtra(EXTRA + "COMMAND_DESCRIPTION", command.take(300))
            }

            fun fail(failure: Failure, message: String) {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                if (cont.isActive) cont.resume(Result("", "", -1, message, failure))
            }

            try {
                // RunCommandService Termuxa od razu przechodzi na pierwszy plan. Na Androidzie 8+
                // zwykly startService() odbijal sie od blokady uslug w tle, gdy Termux nie byl
                // akurat otwarty — polecenie nie dochodzilo, a ustawienia mylaco pokazywaly
                // „brak zgody”.
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                if (started == null) fail(Failure.NotStarted, "Nie znaleziono usługi Termuxa — za stara albo zmieniona wersja.")
            } catch (e: SecurityException) {
                fail(Failure.NoPermission, "Brak uprawnienia do sterowania Termuxem.")
            } catch (e: Exception) {
                fail(Failure.NotStarted, "Android nie uruchomił Termuxa: ${e.message}")
            }

            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }
}
