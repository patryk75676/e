package pl.cyphr.app

/**
 * Maly czytnik Markdownu dla odpowiedzi modeli: bloki kodu, naglowki, punktory,
 * **pogrubienie**, *kursywa* i `kod` w linii. Tyle wystarcza, zeby odpowiedz nie
 * wygladala jak gole gwiazdki i ```. Czysta logika bez Compose — rysuje ChatMarkdown.
 */
object Markdown {
    enum class Style { Plain, Bold, Italic, Code }
    data class Span(val text: String, val style: Style)

    sealed interface Block {
        /** Jedna linia albo akapit tekstu z fragmentami w roznych stylach. */
        data class Text(val spans: List<Span>) : Block
        /** Blok kodu przepisany doslownie. */
        data class Code(val code: String, val language: String) : Block
    }

    private val FENCE = Regex("""^\s*```\s*([\w+#.-]*)\s*$""")
    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")

    fun blocks(text: String): List<Block> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                out += Block.Text(inline(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }

        val lines = text.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flushParagraph()
                val lang = fence.groupValues[1]
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && FENCE.matchEntire(lines[i]) == null) {
                    code += lines[i]
                    i++
                }
                out += Block.Code(code.joinToString("\n"), lang)
                i++ // zamykajacy ``` (albo koniec tekstu — niezamkniety blok nie gubi tresci)
                continue
            }
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val numbered = NUMBERED.matchEntire(line)
            when {
                line.isBlank() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    out += Block.Text(inline(heading.groupValues[1].trim()).map { it.copy(style = Style.Bold) })
                }
                bullet != null -> {
                    flushParagraph()
                    val indent = " ".repeat(bullet.groupValues[1].length)
                    out += Block.Text(listOf(Span("$indent•  ", Style.Plain)) + inline(bullet.groupValues[2]))
                }
                numbered != null -> {
                    flushParagraph()
                    val indent = " ".repeat(numbered.groupValues[1].length)
                    out += Block.Text(listOf(Span("$indent${numbered.groupValues[2]}. ", Style.Plain)) + inline(numbered.groupValues[3]))
                }
                else -> paragraph += line
            }
            i++
        }
        flushParagraph()
        return out
    }

    /** Style w obrebie linii. Znacznik bez pary zostaje zwyklym tekstem. */
    fun inline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) { spans += Span(plain.toString(), Style.Plain); plain.clear() }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) { flush(); spans += Span(text.substring(i + 1, end), Style.Code); i = end + 1; continue }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val mark = text.substring(i, i + 2)
                    val end = text.indexOf(mark, i + 2)
                    if (end > i + 2 && !text[i + 2].isWhitespace() && !text[end - 1].isWhitespace()) {
                        flush(); spans += Span(text.substring(i + 2, end), Style.Bold); i = end + 2; continue
                    }
                }
                c == '*' || c == '_' -> {
                    // Kursywa tylko jako osobne slowo: 2 * 3, 5*5 i plik_moj_nowy.txt zostaja tekstem.
                    val openOk = (i == 0 || !text[i - 1].isLetterOrDigit()) &&
                        i + 1 < text.length && !text[i + 1].isWhitespace()
                    val end = if (openOk) text.indexOf(c, i + 1) else -1
                    val closeOk = end > i + 1 && !text[end - 1].isWhitespace() &&
                        (end + 1 >= text.length || !text[end + 1].isLetterOrDigit())
                    if (closeOk) { flush(); spans += Span(text.substring(i + 1, end), Style.Italic); i = end + 1; continue }
                }
            }
            plain.append(c)
            i++
        }
        flush()
        return spans
    }
}
