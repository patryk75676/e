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
    fun instructions(where: String): String = if (isEn) {
        """

You have access to a terminal: $where
When you need to check or do something, write on a separate line exactly:
!RUN: <command>
One command at a time, with no extra formatting and no code fences.
You'll get the output in the next message — then continue.
Don't guess results — if you don't know something, check it with a command.
A command gets ${COMMAND_TIMEOUT_SECONDS} s and receives no input — don't run
interactive programs or ones that never end (ping without -c, top, editors).
The user confirms every command and may refuse.
        """.trimIndent()
    } else {
        """

Masz dostęp do terminala: $where
Gdy potrzebujesz czegoś sprawdzić lub wykonać, napisz w osobnej linii dokładnie:
!RUN: <polecenie>
Jedno polecenie naraz, bez dodatkowego formatowania i bez znaczników kodu.
Wynik dostaniesz w następnej wiadomości i wtedy kontynuuj.
Nie zgaduj wyników — jeśli czegoś nie wiesz, sprawdź poleceniem.
Polecenie ma ${COMMAND_TIMEOUT_SECONDS} s i nie dostaje nic na wejściu — nie uruchamiaj
programów interaktywnych ani działających bez końca (ping bez -c, top, edytory).
Użytkownik potwierdza każde polecenie i może odmówić.
        """.trimIndent()
    }

    /**
     * Opis terminala dla modelu. Dokladny opis tego, co jest dostepne, oszczedza
     * nieudanych polecen (np. apt w powloce Androida albo sudo w Termuksie).
     */
    suspend fun target(context: Context): String =
        if (Termux.ready(context)) {
            tr(
                "Termux — Linux na Androidzie: bash, pkg install <pakiet>, python, git, curl i to, " +
                    "co użytkownik doinstalował. Katalog domowy ~ to ${Termux.HOME}. " +
                    "Nie ma sudo ani roota — pkg działa bez nich.",
                "Termux — Linux on Android: bash, pkg install <package>, python, git, curl and whatever " +
                    "the user has installed. The home directory ~ is ${Termux.HOME}. " +
                    "There's no sudo or root — pkg works without them.",
            )
        } else {
            tr(
                "powłoka Androida (toybox) w piaskownicy aplikacji: ls, cat, ps, df, getprop, ping -c 3 itp. " +
                    "Nie ma apt, pkg, pythona ani roota.",
                "the Android shell (toybox) in the app's sandbox: ls, cat, ps, df, getprop, ping -c 3 etc. " +
                    "There's no apt, pkg, python or root.",
            )
        }

    /** Wyciaga pierwsze zadane polecenie albo null, gdy model o zadne nie prosi. */
    fun requestedCommand(reply: String): String? =
        CALL.find(reply)?.groupValues?.get(1)?.trim()?.trim('`')?.trim()?.ifBlank { null }

    /** Odpowiedz bez linii z poleceniem — to pokazujemy w dymku rozmowy. */
    fun withoutCall(reply: String): String =
        GAP.replace(EMPTY_FENCE.replace(CALL.replace(reply, ""), ""), "\n\n").trim()

    /** Tyle najdluzej czeka polecenie zlecone przez model, zanim je przerwiemy. */
    const val COMMAND_TIMEOUT_SECONDS = 30L

    /**
     * Uruchamia polecenie i zwraca wynik obciety do rozsadnej dlugosci —
     * caly wynik wrocilby do modelu jako tokeny, za ktore placi uzytkownik.
     * Kazde polecenie ma limit czasu: bez niego `ping` albo `top` wieszaly czat.
     */
    suspend fun execute(context: Context, command: String): String {
        // Termux jest, a zgody jeszcze nie ma — system pyta teraz, przy pierwszym poleceniu.
        TermuxPermission.ensure(context)
        return withContext(Dispatchers.IO) { runCommand(context, command) }
    }

    private suspend fun runCommand(context: Context, command: String): String {
        val raw = if (Termux.ready(context)) {
            val r = Termux.run(context, command, timeoutMs = COMMAND_TIMEOUT_SECONDS * 1000)
            buildString {
                append(listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n"))
                // Blad po stronie Termuxa (np. zablokowane polecenia z zewnatrz) — model ma go zobaczyc.
                r.errMsg?.let { if (isNotEmpty()) append("\n"); append("Termux: ").append(it) }
                if (r.truncated) append(tr("\n(Termux obciął początek wyniku)", "\n(Termux cut off the start of the output)"))
                if (isEmpty()) append(tr("(brak wyniku, kod ${r.exitCode})", "(no output, code ${r.exitCode})"))
            }
        } else {
            try {
                val home = File(context.filesDir, "home").apply { mkdirs() }
                val r = Shell.run(command, home, COMMAND_TIMEOUT_SECONDS, maxChars = 8_000, pidDir = context.cacheDir)
                buildString {
                    append(r.output.ifBlank { tr("(brak wyniku)", "(no output)") })
                    if (r.timedOut) {
                        append(
                            tr(
                                "\n(przerwano po $COMMAND_TIMEOUT_SECONDS s — polecenie się nie kończyło)",
                                "\n(stopped after $COMMAND_TIMEOUT_SECONDS s — the command didn't finish)",
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                tr("Błąd: ${e.message}", "Error: ${e.message}")
            }
        }
        return raw.trimEnd().let { if (it.length > 4000) it.take(4000) + tr("\n… (wynik obcięty)", "\n… (output cut off)") else it }
    }
}
