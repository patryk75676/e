package pl.cyphr.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wykonywanie polecen zleconych przez model.
 *
 * Model nie dostaje zadnego kanalu do systemu. Zamiast tego prosimy go, zeby
 * napisal polecenie w ustalonym formacie, a aplikacja je wychwytuje, pyta
 * uzytkownika o zgode i dopiero wtedy uruchamia. Kazde polecenie przechodzi przez
 * ten sam dialog co polecenia wpisywane recznie — model nie ma drogi na skroty.
 */
object AgentTools {
    /**
     * Znacznik, ktorym model prosi o wykonanie polecenia. Dopuszczamy wciecie
     * i punktor listy, bo modele lubia formatowac odpowiedz.
     */
    private val CALL =
        Regex("""^[ \t]*(?:[-*>]\s*)?!RUN:[ \t]*(.+)(?:\r?\n)?""", RegexOption.MULTILINE)

    /** Pusty blok kodu, ktory zostaje po wycieciu polecenia zamknietego w ```. */
    private val EMPTY_FENCE =
        Regex("""```[a-zA-Z]*[ \t]*(?:\r?\n)?[ \t]*```[ \t]*(?:\r?\n)?""")

    /** Trzy i wiecej pustych linii po wycieciu — skracamy do jednej przerwy. */
    private val GAP = Regex("""\n{3,}""")

    /** Ile razy pod rzad model moze poprosic o polecenie, zanim przerwiemy. */
    const val MAX_ROUNDS = 5

    /** Dopisek do instrukcji systemowej, wlaczany tylko gdy uzytkownik na to pozwolil. */
    fun instructions(where: String): String = """

Masz dostęp do terminala ($where). Gdy potrzebujesz czegoś sprawdzić lub wykonać,
napisz w osobnej linii dokładnie:
!RUN: <polecenie>
Jedno polecenie naraz, bez dodatkowego formatowania i bez znaczników kodu.
Wynik dostaniesz w następnej wiadomości i wtedy kontynuuj.
Nie zgaduj wyników — jeśli czegoś nie wiesz, sprawdź poleceniem.
Użytkownik potwierdza każde polecenie i może odmówić.
    """.trimIndent()

    /** Wyciaga pierwsze zadane polecenie albo null, gdy model o zadne nie prosi. */
    fun requestedCommand(reply: String): String? =
        CALL.find(reply)?.groupValues?.get(1)?.trim()?.trim('`')?.trim()?.ifBlank { null }

    /** Odpowiedz bez linii z poleceniem — to pokazujemy w dymku rozmowy. */
    fun withoutCall(reply: String): String =
        GAP.replace(EMPTY_FENCE.replace(CALL.replace(reply, ""), ""), "\n\n").trim()

    /** Gdzie poleci polecenie przy obecnych ustawieniach. */
    suspend fun target(context: Context): String =
        if (Termux.check(context) == Termux.State.Ready) "Termux" else "powłoka Androida"

    /** Tyle najdluzej czeka polecenie zlecone przez model, zanim je przerwiemy. */
    const val COMMAND_TIMEOUT_SECONDS = 30L

    /**
     * Uruchamia polecenie i zwraca wynik obciety do rozsadnej dlugosci —
     * caly wynik wrocilby do modelu jako tokeny, za ktore placi uzytkownik.
     * Kazde polecenie ma limit czasu: bez niego `ping` albo `top` wieszaly czat.
     */
    suspend fun execute(context: Context, command: String): String = withContext(Dispatchers.IO) {
        val raw = if (Termux.check(context) == Termux.State.Ready) {
            val r = Termux.run(context, command, timeoutMs = COMMAND_TIMEOUT_SECONDS * 1000)
            listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n").ifBlank {
                "(brak wyniku, kod ${r.exitCode})"
            }
        } else {
            try {
                val home = File(context.filesDir, "home").apply { mkdirs() }
                val r = Shell.run(command, home, COMMAND_TIMEOUT_SECONDS, maxChars = 8_000, pidDir = context.cacheDir)
                buildString {
                    append(r.output.ifBlank { "(brak wyniku)" })
                    if (r.timedOut) append("\n(przerwano po $COMMAND_TIMEOUT_SECONDS s — polecenie się nie kończyło)")
                }
            } catch (e: Exception) {
                "Błąd: ${e.message}"
            }
        }
        raw.trimEnd().let { if (it.length > 4000) it.take(4000) + "\n… (wynik obcięty)" else it }
    }
}
