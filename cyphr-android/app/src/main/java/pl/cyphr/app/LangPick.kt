package pl.cyphr.app

import android.content.Context
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.Locale

/**
 * Wybor jezyka aplikacji. Wybrany recznie w Ustawieniach wygrywa zawsze. Bez niego decyduje
 * adres IP: serwer CYPHR (GET /v1/geo) mowi, czy telefon laczy sie z polskiego adresu —
 * wtedy polski, a z kazdego innego angielski. Sprawdzane przy starcie aplikacji i po powrocie
 * do niej po dluzszej przerwie.
 *
 * Zanim serwer odpowie (i gdy odpowiedziec nie umie), obowiazuje ostatni wynik z IP, a przy
 * pierwszym uruchomieniu — kraj sieci komorkowej albo karty SIM i jezyk telefonu.
 */
object LangPick {
    /** Po takiej przerwie powrot do aplikacji sprawdza IP jeszcze raz. */
    const val RECHECK_MS = 60 * 60_000L

    /** Jezyk do pokazania od razu, bez sieci. */
    fun resolve(context: Context): Lang = Prefs.langChoice ?: Prefs.langByIp ?: guess(context)

    fun apply(context: Context) = Lang.set(resolve(context))

    /** Wybor z Ustawien: jezyk na stale albo null — znowu wedlug IP. */
    fun choose(context: Context, lang: Lang?) {
        Prefs.setLangChoice(lang)
        apply(context)
    }

    /** Czy aplikacja zna juz jezyk z IP — przy pierwszym uruchomieniu warto chwile na niego poczekac. */
    val knowsIp: Boolean get() = Prefs.langByIp != null

    /** Pierwsze uruchomienie, zanim serwer odpowie: siec komorkowa, karta SIM, jezyk telefonu. */
    fun guess(context: Context): Lang {
        val tm = try {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        } catch (e: Exception) {
            null
        }
        val country = listOf(tm?.networkCountryIso, tm?.simCountryIso).firstOrNull { !it.isNullOrBlank() }
        return fromCountry(country, Locale.getDefault().language)
    }

    /** Kraj sieci (albo, gdy go brak, jezyk telefonu) -> jezyk aplikacji. */
    internal fun fromCountry(country: String?, deviceLanguage: String?): Lang = when {
        !country.isNullOrBlank() -> if (country.equals("pl", ignoreCase = true)) Lang.PL else Lang.EN
        deviceLanguage.equals("pl", ignoreCase = true) -> Lang.PL
        else -> Lang.EN
    }

    /** Czy pora zapytac serwer jeszcze raz. */
    fun stale(now: Long = System.currentTimeMillis()): Boolean {
        val at = Prefs.langByIpAt
        return at <= 0L || now - at >= RECHECK_MS || now < at
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Deferred<Lang?>? = null

    /**
     * Pyta serwer o jezyk dla adresu IP telefonu — jedno zapytanie naraz. Wynik zapamietuje
     * i, gdy jezyk nie jest wybrany recznie, od razu ustawia. Null, gdy serwer nie odpowiedzial.
     */
    fun refresh(context: Context): Deferred<Lang?> {
        running?.takeIf { it.isActive }?.let { return it }
        val app = context.applicationContext
        return scope.async {
            val lang = Api.geoLang()
            if (lang != null) {
                Prefs.setLangByIp(lang, System.currentTimeMillis())
                apply(app)
            }
            lang
        }.also { running = it }
    }
}
