package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Odpowiedz modelu przychodzi po kilku sekundach. Do tego czasu rozmowa zdazyla
 * sie zmienic — choćby o samo pytanie. Zmiana musi liczyc sie od aktualnej postaci
 * rozmowy, inaczej odpowiedz dopisuje sie do stanu sprzed pytania i pytanie ginie.
 */
class ChatUpdateTest {

    private val q = ChatMessage("Ile to 2+2?", true)
    private val a = ChatMessage("4", false)

    @Test
    fun `odpowiedz dopisuje sie za pytaniem, a nie w jego miejsce`() {
        val start = Chat(id = "c1")
        // Chwila wyslania: pytanie trafia do rozmowy.
        val (afterSend, _) = listOf(start).updated("c1", 1L) { it.copy(messages = it.messages + q) }!!
        // Chwile pozniej: odpowiedz liczona od AKTUALNEJ listy.
        val (afterReply, chat) = afterSend.updated("c1", 2L) { it.copy(messages = it.messages + a) }!!

        assertEquals(listOf(q, a), chat.messages)
        assertEquals(listOf(q, a), afterReply.single().messages)
    }

    @Test
    fun `stara kopia rozmowy gubi pytanie - tak dzialalo przed poprawka`() {
        val stale = Chat(id = "c1")
        val (afterSend, _) = listOf(stale).updated("c1", 1L) { it.copy(messages = it.messages + q) }!!
        // Dawny kod: block(active), gdzie active to rozmowa sprzed pytania.
        val broken = stale.copy(messages = stale.messages + a)
        assertEquals(listOf(a), broken.messages)
        // Poprawka czyta rozmowe z listy, wiec pytanie zostaje.
        val (_, fixed) = afterSend.updated("c1", 2L) { it.copy(messages = it.messages + a) }!!
        assertEquals(listOf(q, a), fixed.messages)
    }

    @Test
    fun `zwiniecie pamieci nie cofa wiadomosci`() {
        val list = listOf(Chat(id = "c1", messages = listOf(q)))
        val (_, chat) = list.updated("c1", 5L) { it.copy(memory = Memory("notatka", 1)) }!!
        assertEquals(listOf(q), chat.messages)
        assertEquals("notatka", chat.memory.summary)
    }

    @Test
    fun `odpowiedz trafia do rozmowy, w ktorej padlo pytanie`() {
        val list = listOf(Chat(id = "c1", messages = listOf(q)), Chat(id = "c2"))
        val (after, _) = list.updated("c1", 9L) { it.copy(messages = it.messages + a) }!!
        assertEquals(listOf(q, a), after.first { it.id == "c1" }.messages)
        assertEquals(emptyList<ChatMessage>(), after.first { it.id == "c2" }.messages)
    }

    @Test
    fun `usunieta w trakcie rozmowa nie wraca`() {
        val list = listOf(Chat(id = "c2"))
        assertNull(list.updated("c1", 9L) { it.copy(messages = it.messages + a) })
    }

    @Test
    fun `zmiana ustawia czas ostatniej zmiany`() {
        val (_, chat) = listOf(Chat(id = "c1", updatedAt = 1L)).updated("c1", 42L) { it }!!
        assertEquals(42L, chat.updatedAt)
    }
}
