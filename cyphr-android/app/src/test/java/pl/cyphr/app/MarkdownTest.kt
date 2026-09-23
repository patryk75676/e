package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Odpowiedzi modeli przychodza w Markdownie — bez tego widac gole gwiazdki i ```. */
class MarkdownTest {

    private fun spans(text: String) = (Markdown.blocks(text).single() as Markdown.Block.Text).spans

    @Test
    fun `zwykly tekst to jeden akapit bez stylow`() {
        assertEquals(listOf(Markdown.Span("Ala ma kota", Markdown.Style.Plain)), spans("Ala ma kota"))
    }

    @Test
    fun `pogrubienie i kod w linii`() {
        assertEquals(
            listOf(
                Markdown.Span("To jest ", Markdown.Style.Plain),
                Markdown.Span("ważne", Markdown.Style.Bold),
                Markdown.Span(" i ", Markdown.Style.Plain),
                Markdown.Span("ls -la", Markdown.Style.Code),
                Markdown.Span(".", Markdown.Style.Plain),
            ),
            spans("To jest **ważne** i `ls -la`."),
        )
    }

    @Test
    fun `kursywa gwiazdka i podkreslnikiem`() {
        assertEquals(
            listOf(
                Markdown.Span("a ", Markdown.Style.Plain),
                Markdown.Span("b", Markdown.Style.Italic),
                Markdown.Span(" c ", Markdown.Style.Plain),
                Markdown.Span("d", Markdown.Style.Italic),
            ),
            spans("a *b* c _d_"),
        )
    }

    @Test
    fun `blok kodu zostaje doslownie razem z jezykiem`() {
        val blocks = Markdown.blocks("Uruchom:\n```bash\necho **nie pogrubiaj**\nls\n```\nGotowe.")
        assertEquals(3, blocks.size)
        assertEquals(Markdown.Block.Code("echo **nie pogrubiaj**\nls", "bash"), blocks[1])
        assertEquals("Gotowe.", (blocks[2] as Markdown.Block.Text).spans.single().text)
    }

    @Test
    fun `niezamkniety blok kodu nie gubi tresci`() {
        val blocks = Markdown.blocks("```\nlinia 1\nlinia 2")
        assertEquals(Markdown.Block.Code("linia 1\nlinia 2", ""), blocks.single())
    }

    @Test
    fun `naglowki i punktory`() {
        val blocks = Markdown.blocks("## Plan\n- pierwszy\n* drugi\n1. trzeci")
        val lines = blocks.map { (it as Markdown.Block.Text) }
        assertEquals(Markdown.Style.Bold, lines[0].spans.single().style)
        assertEquals("Plan", lines[0].spans.single().text)
        assertEquals("•  pierwszy", lines[1].spans.joinToString("") { it.text })
        assertEquals("•  drugi", lines[2].spans.joinToString("") { it.text })
        assertEquals("1. trzeci", lines[3].spans.joinToString("") { it.text })
    }

    @Test
    fun `samotna gwiazdka i mnozenie nie psuja tekstu`() {
        assertEquals("2 * 3 = 6", spans("2 * 3 = 6").joinToString("") { it.text })
        assertEquals("5*5", spans("5*5").joinToString("") { it.text })
    }

    @Test
    fun `podkreslnik w nazwie nie robi kursywy`() {
        assertEquals(listOf(Markdown.Span("plik_moj_nowy.txt", Markdown.Style.Plain)), spans("plik_moj_nowy.txt"))
    }

    @Test
    fun `pusta odpowiedz nie ma blokow`() {
        assertEquals(emptyList<Markdown.Block>(), Markdown.blocks(""))
    }
}
