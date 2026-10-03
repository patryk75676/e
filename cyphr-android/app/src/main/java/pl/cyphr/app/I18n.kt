package pl.cyphr.app

import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/**
 * Jezyk aplikacji: polski albo angielski. Wybiera go adres IP (polski dla polskich adresow,
 * angielski dla reszty — [LangPick]), chyba ze uzytkownik ustawil jezyk sam w Ustawieniach.
 *
 * Teksty sa w kodzie parami, obok siebie: `tr("Zapisz", "Save")`. Jezyk siedzi w stanie Compose,
 * wiec ekran, ktory czyta teksty przez [tr], po zmianie jezyka przerysowuje sie sam.
 */
enum class Lang(val code: String) {
    PL("pl"),
    EN("en");

    companion object {
        private val state = mutableStateOf(PL)

        val current: Lang get() = state.value

        fun set(lang: Lang) {
            if (state.value != lang) state.value = lang
        }

        fun of(code: String?): Lang? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }
    }
}

val isEn: Boolean get() = Lang.current == Lang.EN

/** Tekst w jezyku aplikacji. */
fun tr(pl: String, en: String): String = if (Lang.current == Lang.EN) en else pl

/** Daty i liczby w jezyku aplikacji: „niedziela”, „1 234,50” albo „Sunday”, „1,234.50”. */
val appLocale: Locale get() = if (Lang.current == Lang.EN) Locale.ENGLISH else Locale("pl", "PL")

/**
 * Liczba z rzeczownikiem. Polski ma trzy formy („1 plik”, „3 pliki”, „5 plików”),
 * angielski dwie („1 file”, „3 files”).
 */
fun count(n: Int, one: String, few: String, many: String, enOne: String, enMany: String): String =
    if (isEn) "$n ${if (n == 1) enOne else enMany}"
    else "$n ${plForm(n, one, few, many)}"

fun plForm(n: Int, one: String, few: String, many: String): String = when {
    n == 1 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}
