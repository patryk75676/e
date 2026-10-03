package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ktora rozmowa czeka po powrocie do aplikacji. */
class LandingTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun decide(
        lastSeen: Long? = now - 5 * 60_000,
        lastChat: String? = "ostatnia",
        unseen: String? = null,
        pending: String? = null,
        afterSeconds: Int = 3600,
    ) = Landing.decide(now, lastSeen, lastChat, unseen, pending, afterSeconds)

    @Test
    fun `krotka przerwa wraca do ostatniej rozmowy`() {
        assertEquals(Landing.At("ostatnia"), decide())
    }

    @Test
    fun `po dluzszej przerwie nowa rozmowa`() {
        assertEquals(Landing.Fresh, decide(lastSeen = now - hour))
        assertEquals(Landing.Fresh, decide(lastSeen = now - 30 * hour))
    }

    @Test
    fun `tuz przed progiem jeszcze ostatnia rozmowa`() {
        assertEquals(Landing.At("ostatnia"), decide(lastSeen = now - hour + 1_000))
    }

    @Test
    fun `odpowiedz z tla wygrywa z przerwa`() {
        assertEquals(Landing.At("z-tla"), decide(lastSeen = now - 30 * hour, unseen = "z-tla"))
    }

    @Test
    fun `prosba o zgode wygrywa ze wszystkim`() {
        assertEquals(Landing.At("zgoda"), decide(lastSeen = now - 30 * hour, unseen = "z-tla", pending = "zgoda"))
    }

    @Test
    fun `Nigdy wylacza nowa rozmowe`() {
        assertEquals(Landing.At("ostatnia"), decide(lastSeen = now - 300 * hour, afterSeconds = 0))
    }

    @Test
    fun `cofniety zegar nie liczy sie jako przerwa`() {
        assertEquals(Landing.At("ostatnia"), decide(lastSeen = now + 5 * hour))
    }

    @Test
    fun `pierwsze uruchomienie bez historii nic nie zmienia`() {
        assertNull(decide(lastSeen = null, lastChat = null))
        assertEquals(Landing.At("ostatnia"), decide(lastSeen = null))
    }
}
