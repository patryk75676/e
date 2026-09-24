package pl.cyphr.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Termux jednym przyciskiem: CYPHR pobiera oficjalny plik z F-Droid, sprawdza, ze to naprawde
 * Termux podpisany przez F-Droid, i otwiera instalator Androida. Instalacje zatwierdza uzytkownik
 * w oknie systemu — CYPHR niczego nie instaluje sam i nie instaluje niczego poza tym plikiem.
 */
object TermuxInstall {
    /** Odcisk certyfikatu, ktorym F-Droid podpisuje Termuksa (SHA-256 certyfikatu, jak w apksigner). */
    const val FDROID_SIGNER = "228fb2cfe90831c1499ec3ccaf61e96e8e1ce70766b9474672ce427334d41c42"

    /** Strona Termuksa w F-Droid — gdy pobieranie w aplikacji sie nie uda. */
    const val FDROID_PAGE = "https://f-droid.org/packages/com.termux/"

    /** Adresy F-Droid; w testach podmieniane na lokalny serwer. */
    internal var apiUrl = "https://f-droid.org/api/v1/packages/com.termux"
    internal var apkUrl = "https://f-droid.org/repo/com.termux_%d.apk"

    /** Co jest w pobranym pliku: pakiet i odciski podpisow. W testach podmieniane. */
    internal var inspect: (Context, File) -> Pair<String?, List<String>> = ::inspectArchive

    private const val DIR = "termux"
    private const val MIN_FREE_BYTES = 50L * 1024 * 1024

    sealed interface Step {
        object Idle : Step
        /** [total] < 0 — rozmiar jeszcze nieznany. */
        data class Downloading(val done: Long, val total: Long) : Step
        object Verifying : Step
        data class Ready(val file: File) : Step
        data class Failed(val message: String) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    /** Trwajace zapytanie — przerwanie pobierania zrywa je od razu, a nie po kolejnym kawalku. */
    @Volatile private var call: okhttp3.Call? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // Plik ma ponad 100 MB — liczy sie brak postepu, a nie czas calego pobierania.
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Pobiera i sprawdza Termuksa. Drugie wywolanie w trakcie nic nie robi. */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val current = _step.value
        if (current is Step.Ready && current.file.exists()) return
        val app = context.applicationContext
        _step.value = Step.Downloading(0, -1)
        job = scope.launch {
            var file: File? = null
            try {
                val got = withContext(Dispatchers.IO) {
                    download(app) { done, total ->
                        // Po przerwaniu spozniony postep nie moze przywrocic paska.
                        if (_step.value is Step.Downloading) _step.value = Step.Downloading(done, total)
                    }
                }
                file = got
                _step.value = Step.Verifying
                val (pkg, signers) = withContext(Dispatchers.IO) { inspect(app, got) }
                if (!genuine(pkg, signers)) {
                    got.delete()
                    _step.value = Step.Failed(
                        "Pobrany plik nie jest oryginalnym Termuksem z F-Droid — został usunięty. " +
                            "Spróbuj ponownie albo pobierz Termuksa ze strony F-Droid.",
                    )
                    return@launch
                }
                _step.value = Step.Ready(got)
            } catch (e: CancellationException) {
                file?.delete()
                throw e
            } catch (e: Exception) {
                file?.delete()
                // Przerwane przyciskiem — zerwane polaczenie to skutek, nie blad do pokazania.
                if (!isActive) return@launch
                _step.value = Step.Failed(
                    when (e) {
                        is InstallError -> e.message ?: "Nie udało się pobrać Termuksa."
                        is IOException -> "Nie udało się pobrać Termuksa. Sprawdź internet i spróbuj ponownie."
                        else -> "Nie udało się pobrać Termuksa. Spróbuj ponownie."
                    },
                )
            }
        }
    }

    /** Przerywa pobieranie i usuwa niedokonczony plik. */
    fun cancel(context: Context) {
        job?.cancel()
        job = null
        call?.cancel()
        _step.value = Step.Idle
        val dir = File(context.cacheDir, DIR)
        scope.launch(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
    }

    /**
     * Sprzata pobrany plik, gdy juz niepotrzebny (Termux zainstalowany) albo zostal po dawnym
     * pobieraniu. Ponad 100 MB w pamieci podrecznej to za duzo, zeby lezalo bez powodu.
     */
    fun cleanup(context: Context, installed: Boolean, now: Long = System.currentTimeMillis()) {
        if (job?.isActive == true) return
        val dir = File(context.cacheDir, DIR)
        val files = dir.listFiles().orEmpty()
        if (files.isEmpty()) return
        val old = files.filter { installed || now - it.lastModified() > 2 * 24 * 3600_000L }
        old.forEach { it.delete() }
        val ready = _step.value
        if (ready is Step.Ready && !ready.file.exists()) _step.value = Step.Idle
    }

    private class InstallError(message: String) : Exception(message)

    private fun execute(request: Request): okhttp3.Response {
        val c = client.newCall(request)
        call = c
        return c.execute()
    }

    private suspend fun download(context: Context, progress: (Long, Long) -> Unit): File {
        val active = kotlin.coroutines.coroutineContext
        val version = execute(Request.Builder().url(apiUrl).build()).use { r ->
            if (!r.isSuccessful) throw InstallError("F-Droid chwilowo nie odpowiada. Spróbuj za chwilę.")
            pickVersion(r.body?.string().orEmpty())
        } ?: throw InstallError("F-Droid nie podał wersji Termuksa. Spróbuj za chwilę.")

        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "com.termux_$version.apk.part")
        val done = File(dir, "com.termux_$version.apk")
        execute(Request.Builder().url(String.format(java.util.Locale.ROOT, apkUrl, version)).build()).use { r ->
            if (!r.isSuccessful) throw InstallError("F-Droid chwilowo nie oddaje pliku Termuksa. Spróbuj za chwilę.")
            val body = r.body ?: throw IOException("pusta odpowiedz")
            val total = body.contentLength()
            if (total > 0 && dir.usableSpace < total + MIN_FREE_BYTES) {
                throw InstallError("Za mało miejsca w telefonie — Termux potrzebuje ok. ${(total + MIN_FREE_BYTES) / 1048576} MB.")
            }
            var got = 0L
            var shown = 0L
            try {
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            got += n
                            // Co ~1 MB — ekran nie musi odswiezac sie przy kazdych 64 KB.
                            if (got - shown >= 1024 * 1024) { progress(got, total); shown = got }
                            active.ensureActive()
                        }
                    }
                }
                if (total > 0 && got != total) throw IOException("niepelny plik")
                if (!part.renameTo(done)) throw IOException("zapis")
            } catch (e: Throwable) {
                // Niedokonczony plik nie moze zostac — ma ponad 100 MB i nie da sie go uzyc.
                part.delete()
                throw e
            }
            progress(got, if (total > 0) total else got)
        }
        return done
    }

    /** Wersja z odpowiedzi F-Droid: polecana, a bez niej najnowsza nie-beta. */
    internal fun pickVersion(json: String): Int? = try {
        val o = JSONObject(json)
        val suggested = o.optInt("suggestedVersionCode", 0)
        if (suggested > 0) {
            suggested
        } else {
            val arr = o.optJSONArray("packages")
            (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
                .filter { !it.optString("versionName").contains("beta", ignoreCase = true) }
                .maxOfOrNull { it.optInt("versionCode") }
                ?.takeIf { it > 0 }
        }
    } catch (e: Exception) {
        null
    }

    /** Oryginalny Termux: wlasciwy pakiet i wylacznie podpis F-Droid. */
    internal fun genuine(packageName: String?, signers: List<String>): Boolean =
        packageName == Termux.PACKAGE && signers.isNotEmpty() && signers.all { it.equals(FDROID_SIGNER, ignoreCase = true) }

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Pakiet i podpisy z pliku APK — tak, jak zobaczy je instalator Androida. */
    @Suppress("DEPRECATION")
    private fun inspectArchive(context: Context, file: File): Pair<String?, List<String>> {
        // GET_SIGNATURES tez: Android 9 zbiera podpisy archiwum tylko przy tej fladze.
        val info: PackageInfo = context.packageManager.getPackageArchiveInfo(
            file.path,
            PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: return null to emptyList()
        val signers = if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            info.signingInfo.apkContentsSigners?.toList().orEmpty()
        } else {
            info.signatures?.toList().orEmpty()
        }
        return info.packageName to signers.map { sha256(it.toByteArray()) }
    }

    // ---------------- instalacja i odinstalowanie przez system ----------------

    /** Czy Android pozwala CYPHR otworzyc instalator (Ustawienia → „Instalowanie nieznanych aplikacji”). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Systemowy ekran z przelacznikiem „Zezwalaj z tego zrodla” dla CYPHR. */
    fun allowInstallIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Instalator Androida z pobranym plikiem — tylko do odczytu, tylko ten plik. */
    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Systemowe okno „Odinstalowac Termux?” — potwierdza je uzytkownik. */
    fun uninstallIntent(): Intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${Termux.PACKAGE}"))

    fun pageIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_PAGE))

    /** „34 z 109 MB”, a gdy rozmiar nieznany — „34 MB”. */
    internal fun progressLabel(done: Long, total: Long): String {
        val mb = { b: Long -> (b + 524_288) / 1_048_576 }
        return if (total > 0) "${mb(done)} z ${mb(total)} MB" else "${mb(done)} MB"
    }
}
