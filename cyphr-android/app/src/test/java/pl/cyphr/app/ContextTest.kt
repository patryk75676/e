package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testy zwijania kontekstu — najbardziej zawilej czesci, ktora decyduje co leci do modelu. */
class ContextTest {

    private fun msgs(vararg pairs: Pair<String, Boolean>) =
        pairs.map { ChatMessage(it.first, it.second) }

    private fun long(chars: Int) = "x".repeat(chars)

    @Test
    fun `pusty tekst to zero tokenow`() {
        assertEquals(0, estimateTokens(""))
        assertEquals(0, estimateTokens("   "))
    }

    @Test
    fun `szacowanie rosnie z dlugoscia`() {
        assertTrue(estimateTokens(long(100)) < estimateTokens(long(1000)))
        // 3.5 znaku na token
        assertEquals(2, estimateTokens("siema"))
    }

    @Test
    fun `bez zwijania cala historia jest swieza`() {
        val m = msgs("a" to true, "b" to false, "c" to true)
        assertEquals(3, freshOf(m, Memory()).size)
    }

    @Test
    fun `zwiniete wiadomosci wypadaja ze swiezych`() {
        val m = msgs("a" to true, "b" to false, "c" to true, "d" to false)
        val fresh = freshOf(m, Memory(summary = "notatka", folded = 2))
        assertEquals(2, fresh.size)
        assertEquals("c", fresh.first().text)
    }

    @Test
    fun `licznik zwinietych wiekszy niz historia nie wywala`() {
        val m = msgs("a" to true)
        assertEquals(0, freshOf(m, Memory(folded = 99)).size)
    }

    @Test
    fun `krotka rozmowa nie jest zwijana`() {
        val m = (1..5).map { ChatMessage(long(5000), it % 2 == 1) }
        assertFalse(shouldFold(m, Memory()))
    }

    @Test
    fun `dluga rozmowa jest zwijana`() {
        // 20 wiadomosci po 5000 znakow — duzo ponad prog nawet po odjeciu swiezych
        val m = (1..20).map { ChatMessage(long(5000), it % 2 == 1) }
        assertTrue(shouldFold(m, Memory()))
    }

    @Test
    fun `swieze wiadomosci nie licza sie do progu zwijania`() {
        // Same wiadomosci, ale wszystkie miesza sie w oknie doslownym
        val m = (1..KEEP_VERBATIM).map { ChatMessage(long(99999), true) }
        assertFalse(shouldFold(m, Memory()))
    }

    @Test
    fun `po zwinieciu prog liczy sie od nowa`() {
        val m = (1..20).map { ChatMessage(long(5000), it % 2 == 1) }
        val po = Memory(summary = "notatka", folded = 12)
        // zostalo 8 swiezych, czyli dokladnie okno doslowne
        assertFalse(shouldFold(m, po))
    }
}
