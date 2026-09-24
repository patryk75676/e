package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model prosi o polecenie znacznikiem w tekscie. Te testy pilnuja, zeby
 * znacznik dalo sie znalezc mimo formatowania i zeby nie wyciekl do dymka.
 */
class AgentToolsTest {

    @Test
    fun `zwykla prosba o polecenie`() {
        assertEquals("ls -la", AgentTools.requestedCommand("!RUN: ls -la"))
    }

    @Test
    fun `polecenie w srodku odpowiedzi`() {
        val reply = "Sprawdzę katalog.\n!RUN: ls -la\nZa chwilę wrócę z wynikiem."
        assertEquals("ls -la", AgentTools.requestedCommand(reply))
    }

    @Test
    fun `brak znacznika to brak polecenia`() {
        assertNull(AgentTools.requestedCommand("Nie potrzebuję terminala, odpowiem od razu."))
    }

    @Test
    fun `sam znacznik bez polecenia jest ignorowany`() {
        assertNull(AgentTools.requestedCommand("!RUN:   "))
    }

    @Test
    fun `wciecie i punktor nie przeszkadzaja`() {
        assertEquals("pwd", AgentTools.requestedCommand("  - !RUN: pwd"))
    }

    @Test
    fun `odwrotne apostrofy wokol polecenia sa zdejmowane`() {
        assertEquals("whoami", AgentTools.requestedCommand("!RUN: `whoami`"))
    }

    @Test
    fun `bierzemy pierwsze polecenie`() {
        val reply = "!RUN: pwd\n!RUN: ls"
        assertEquals("pwd", AgentTools.requestedCommand(reply))
    }

    @Test
    fun `dymek nie pokazuje znacznika`() {
        val reply = "Sprawdzę katalog.\n!RUN: ls -la\nWrócę z wynikiem."
        val shown = AgentTools.withoutCall(reply)
        assertTrue(!shown.contains("!RUN"))
        assertEquals("Sprawdzę katalog.\nWrócę z wynikiem.", shown)
    }

    @Test
    fun `pusty blok kodu po wycieciu znika`() {
        val reply = "Sprawdzam:\n```bash\n!RUN: ls\n```\nGotowe."
        val shown = AgentTools.withoutCall(reply)
        assertTrue(!shown.contains("```"))
        assertTrue(!shown.contains("!RUN"))
    }

    @Test
    fun `odpowiedz bez polecenia zostaje nietknieta`() {
        val reply = "Katalog zawiera trzy pliki."
        assertEquals(reply, AgentTools.withoutCall(reply))
    }

    @Test
    fun `po wycieciu nie zostaje pustka miedzy akapitami`() {
        val reply = "Akapit pierwszy.\n\n!RUN: ls\n\nAkapit drugi."
        assertEquals("Akapit pierwszy.\n\nAkapit drugi.", AgentTools.withoutCall(reply))
    }

    @Test
    fun `limit rund jest dodatni i skonczony`() {
        assertTrue(AgentTools.MAX_ROUNDS in 1..10)
    }

    @Test
    fun `instrukcja mowi modelowi gdzie trafi polecenie`() {
        val text = AgentTools.instructions("Termux")
        assertTrue(text.contains("Termux"))
        assertTrue(text.contains("!RUN:"))
    }
}
