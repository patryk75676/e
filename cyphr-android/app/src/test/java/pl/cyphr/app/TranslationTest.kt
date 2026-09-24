package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pilnuje par tekstow w kodzie: kazde tr(...) ma dokladnie dwa teksty, a angielski nie
 * zawiera polskich liter — tak wychodzi zamiana kolejnosci albo zapomniane tlumaczenie.
 */
class TranslationTest {

    private val sources = File("src/main/java/pl/cyphr/app")
    private val polish = Regex("[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ„”]")

    /** Argumenty wywolania od nawiasu [open]: teksty z szablonami i zagniezdzonymi nawiasami. */
    private fun arguments(src: String, open: Int): List<String> {
        val args = ArrayList<String>()
        var depth = 0
        var start = open + 1
        var i = open
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' -> { i = skipString(src, i); continue }
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> {
                    depth--
                    if (depth == 0) {
                        args += src.substring(start, i)
                        return args.map { it.trim() }.filter { it.isNotEmpty() }
                    }
                }
                c == ',' && depth == 1 -> { args += src.substring(start, i); start = i + 1 }
            }
            i++
        }
        error("niedomkniete wywolanie")
    }

    /** Pozycja za koncem napisu zaczynajacego sie w [at] (z obsluga ${...} i ucieczek). */
    private fun skipString(src: String, at: Int): Int {
        if (src.startsWith("\"\"\"", at)) return src.indexOf("\"\"\"", at + 3) + 3
        var i = at + 1
        while (i < src.length) {
            when {
                src[i] == '\\' -> i += 2
                src[i] == '"' -> return i + 1
                src.startsWith("\${", i) -> {
                    var depth = 1
                    i += 2
                    while (depth > 0) {
                        when (src[i]) {
                            '{' -> depth++
                            '}' -> depth--
                            '"' -> { i = skipString(src, i); continue }
                        }
                        i++
                    }
                }
                else -> i++
            }
        }
        error("niedomkniety napis")
    }

    @Test
    fun `kazde tr ma polski i angielski tekst, angielski bez polskich liter`() {
        val files = sources.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("brak zrodel w ${sources.absolutePath}", files.isNotEmpty())
        val call = Regex("""(?<![\w.])tr\(""")
        var count = 0
        val problems = ArrayList<String>()
        for (f in files) {
            val src = f.readText()
            if (f.name == "I18n.kt") continue
            for (m in call.findAll(src)) {
                val line = src.substring(0, m.range.first).count { it == '\n' } + 1
                val args = arguments(src, m.range.last)
                count++
                if (args.size != 2) { problems += "${f.name}:$line: ${args.size} argumenty"; continue }
                if (polish.containsMatchIn(args[1])) problems += "${f.name}:$line: polskie litery w angielskim tekscie: ${args[1].take(80)}"
                // Przekazanie gotowych par dalej (tr(pl, en), tr(DEFAULT_PL, DEFAULT_EN)) jest w porzadku;
                // tekst tylko po jednej stronie — nie.
                if (args[0].contains('"') != args[1].contains('"')) problems += "${f.name}:$line: tekst tylko po jednej stronie"
            }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
        // Tlumaczen jest kilkaset — mniej znaczy, ze test przestal je widziec.
        assertTrue("znaleziono tylko $count wywolan tr()", count > 300)
    }
}
