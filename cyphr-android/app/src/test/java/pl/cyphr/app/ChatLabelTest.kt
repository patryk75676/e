package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nazwa rozmowy powstaje z pierwszej wiadomosci uzytkownika. */
class ChatLabelTest {

    @Test
    fun `pusta rozmowa ma nazwe domyslna`() {
        assertEquals("Nowa rozmowa", Chat().label)
    }

    @Test
    fun `nazwa bierze sie z pierwszej wiadomosci uzytkownika`() {
        val c = Chat(messages = listOf(ChatMessage("Jak ugotowac ryz?", true)))
        assertEquals("Jak ugotowac ryz?", c.label)
    }

    @Test
    fun `odpowiedz modelu nie nadaje nazwy`() {
        val c = Chat(
            messages = listOf(
                ChatMessage("Witaj, w czym pomoc?", false),
                ChatMessage("Pytanie uzytkownika", true),
            ),
        )
        assertEquals("Pytanie uzytkownika", c.label)
    }

    @Test
    fun `wlasna nazwa ma pierwszenstwo`() {
        val c = Chat(title = "Projekt X", messages = listOf(ChatMessage("cokolwiek", true)))
        assertEquals("Projekt X", c.label)
    }

    @Test
    fun `dluga wiadomosc jest przycinana`() {
        val c = Chat(messages = listOf(ChatMessage("a".repeat(200), true)))
        assertEquals(40, c.label.length)
    }

    @Test
    fun `nowe linie nie lamia nazwy`() {
        val c = Chat(messages = listOf(ChatMessage("pierwsza\ndruga\ntrzecia", true)))
        assertTrue(!c.label.contains("\n"))
        assertEquals("pierwsza druga trzecia", c.label)
    }

    @Test
    fun `sama spacja nie staje sie nazwa`() {
        val c = Chat(messages = listOf(ChatMessage("   ", true)))
        assertEquals("Nowa rozmowa", c.label)
    }
}
