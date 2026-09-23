package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Usuwanie pojedynczej wiadomosci i klucze dymkow, ktore przy tym nie skacza. */
class MessageActionsTest {
    private val q1 = ChatMessage("Pierwsze pytanie", true)
    private val a1 = ChatMessage("Pierwsza odpowiedź", false)
    private val q2 = ChatMessage("Drugie pytanie", true)
    private val a2 = ChatMessage("Druga odpowiedź", false)

    @Test
    fun `usuwa dokladnie te wiadomosc, reszta zostaje w kolejnosci`() {
        val chat = Chat(id = "c", messages = listOf(q1, a1, q2, a2))
        assertEquals(listOf(q1, a1, a2), chat.withoutMessage(2).messages)
    }

    @Test
    fun `usuniecie zwinietej wiadomosci zmniejsza licznik zwinietych`() {
        val chat = Chat(id = "c", messages = listOf(q1, a1, q2, a2), memory = Memory("notatka", folded = 2))
        val after = chat.withoutMessage(0)
        assertEquals(1, after.memory.folded)
        assertEquals("notatka", after.memory.summary)
        // Do modelu nadal ida dokladnie te same swieze wiadomosci co przed usunieciem.
        assertEquals(freshOf(chat.messages, chat.memory), freshOf(after.messages, after.memory))
    }

    @Test
    fun `usuniecie swiezej wiadomosci nie rusza zwinietych`() {
        val chat = Chat(id = "c", messages = listOf(q1, a1, q2, a2), memory = Memory("notatka", folded = 2))
        val after = chat.withoutMessage(3)
        assertEquals(2, after.memory.folded)
        assertEquals(listOf(q2), freshOf(after.messages, after.memory))
    }

    @Test
    fun `zly indeks niczego nie zmienia`() {
        val chat = Chat(id = "c", messages = listOf(q1, a1))
        assertSame(chat, chat.withoutMessage(5))
        assertSame(chat, chat.withoutMessage(-1))
    }

    @Test
    fun `tytul z pierwszej wiadomosci idzie za tym, co zostalo`() {
        val chat = Chat(id = "c", messages = listOf(q1, a1, q2, a2))
        assertEquals("Drugie pytanie", chat.withoutMessage(0).label)
        assertEquals("Nowa rozmowa", Chat(id = "c", messages = listOf(q1)).withoutMessage(0).label)
    }

    @Test
    fun `klucze dymkow sa unikalne takze dla powtorzonej tresci`() {
        val ok = ChatMessage("ok", true)
        val keys = messageKeys(listOf(ok, ok, ChatMessage("ok", false), ok))
        assertEquals(4, keys.toSet().size)
    }

    @Test
    fun `po usunieciu wiadomosci pozostale dymki zachowuja klucze`() {
        val before = messageKeys(listOf(q1, a1, q2, a2))
        val after = messageKeys(listOf(q1, a1, a2))
        assertEquals(listOf(before[0], before[1], before[3]), after)
    }
}
