package pl.cyphr.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logowanie Google prosi o token dla klienta typu „Aplikacja internetowa”. Klient typu
 * Android (pakiet + SHA-1) musi tylko istniec w tym samym projekcie — jego identyfikator
 * wpisany tutaj sprawial, ze Google odrzucal kazde logowanie kodem 10.
 */
class GoogleConfigTest {
    private val id = BuildConfig.GOOGLE_WEB_CLIENT_ID

    @Test
    fun `identyfikator ma postac klienta OAuth`() {
        assertTrue(id, id.matches(Regex("""\d+-[a-z0-9]+\.apps\.googleusercontent\.com""")))
    }

    @Test
    fun `to nie jest identyfikator klienta Android`() {
        assertFalse(id, id.startsWith("601323249162-hmcc6q"))
    }

    @Test
    fun `klient Web jest w projekcie klienta Android`() {
        // Klient Android z pakietem pl.cyphr.app i SHA-1 BE:B9:D6:34:... jest w projekcie 601323249162.
        assertTrue(id, id.startsWith("601323249162-"))
    }
}
