package pl.cyphr.app

/**
 * Tworzenie obrazow w rozmowie. Tak jak przy poleceniach terminala model nie dostaje
 * zadnego kanalu — pisze prosbe w ustalonym formacie, a aplikacja ja wychwytuje i prosi
 * serwer CYPHR o obraz. O dziennym limicie decyduje serwer, nie aplikacja.
 */
object ImageTools {
    private val CALL =
        Regex("""^[ \t]*(?:[-*>]\s*)?!IMAGE:[ \t]*(.+)(?:\r?\n)?""", RegexOption.MULTILINE)
    private val EMPTY_FENCE =
        Regex("""```[a-zA-Z]*[ \t]*(?:\r?\n)?[ \t]*```[ \t]*(?:\r?\n)?""")
    private val GAP = Regex("""\n{3,}""")
    private val ASPECT = Regex("""(?:^|\s)--ar\s+(\d{1,2})\s*:\s*(\d{1,2})\s*$""")

    /** Prosba modelu: opis obrazu i proporcje ("1:1", "16:9", "9:16"...). */
    data class Request(val prompt: String, val aspect: String)

    fun requested(reply: String): Request? {
        val raw = CALL.find(reply)?.groupValues?.get(1)?.trim()?.trim('`')?.trim() ?: return null
        val m = ASPECT.find(raw)
        val prompt = (if (m != null) raw.substring(0, m.range.first) else raw).trim().trim('"', '„', '”').trim()
        if (prompt.isBlank()) return null
        val aspect = m?.let { normalize(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: "1:1"
        return Request(prompt.take(1500), aspect)
    }

    /**
     * Proporcje sprowadzone do trzech, ktore obsluguje kazdy model obrazow. Granica 4:3 lezy
     * dokladnie w polowie drogi miedzy kwadratem a 16:9, wiec 5:4 zostaje kwadratem, a 3:2 — szerokie.
     */
    private fun normalize(w: Int, h: Int): String = when {
        w <= 0 || h <= 0 -> "1:1"
        w * 3 >= h * 4 -> "16:9"
        h * 3 >= w * 4 -> "9:16"
        else -> "1:1"
    }

    /** Odpowiedz bez linii z prosba — to widzi uzytkownik nad obrazem. */
    fun withoutCall(reply: String): String =
        GAP.replace(EMPTY_FENCE.replace(CALL.replace(reply, ""), ""), "\n\n").trim()

    /** Dopisek do instrukcji systemowej, gdy serwer umie tworzyc obrazy. */
    fun instructions(left: Int, limit: Int): String = if (left > 0) {
        """
Możesz tworzyć obrazy. Gdy użytkownik prosi o obraz, zdjęcie, rysunek, grafikę, logo albo ilustrację,
napisz w osobnej linii dokładnie:
!IMAGE: <szczegółowy opis obrazu po angielsku> --ar <1:1, 16:9 albo 9:16>
Jeden obraz na odpowiedź, bez znaczników kodu. Obraz pojawi się pod Twoją wiadomością —
nie opisuj go od nowa i nie pisz, że nie potrafisz tworzyć obrazów.
Nigdy nie twórz obrazów nagich ani erotycznych z prawdziwymi, istniejącymi osobami
ani z kimkolwiek, kto może być niepełnoletni — wtedy odmów bez !IMAGE.
Dziś użytkownik może stworzyć jeszcze $left z $limit obrazów.
        """.trimIndent()
    } else {
        """
Dzisiejszy limit obrazów ($limit) jest wykorzystany. Gdy użytkownik prosi o obraz, powiedz krótko,
że kolejne obrazy będą dostępne jutro. Nie używaj !IMAGE.
        """.trimIndent()
    }
}

/** Dzienny limit obrazow na koncie, tak jak widzi go serwer. */
data class ImageQuota(val limit: Int, val used: Int, val left: Int)
