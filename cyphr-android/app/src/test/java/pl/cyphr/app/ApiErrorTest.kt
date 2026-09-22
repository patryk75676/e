package pl.cyphr.app

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serwer nie zawsze odpowiada JSON-em — limiter i Passenger wysylaja goly tekst.
 * Uzytkownik ma wtedy dostac konkret, a nie "Cos poszlo nie tak".
 */
class ApiErrorTest {

    @Test
    fun `limiter mowi ile czekac`() {
        val m = Api.explain(429, 300)
        assertTrue(m, m.contains("5 min"))
        assertTrue(m, !m.contains("Coś poszło nie tak"))
    }

    @Test
    fun `krotkie oczekiwanie podane w sekundach`() {
        assertTrue(Api.explain(429, 30).contains("30 s"))
    }

    @Test
    fun `limiter bez naglowka wciaz jest zrozumialy`() {
        val m = Api.explain(429, null)
        assertTrue(m, m.contains("Odczekaj"))
    }

    /**
     * Limiter liczy zapytania, nie bledne kody. Komunikat nie moze sugerowac,
     * ze uzytkownik sie pomylil — bo dostaje go po trzech zapytaniach pod rzad,
     * nawet gdy wpisal wszystko dobrze.
     */
    @Test
    fun `limiter nie obwinia uzytkownika o bledne proby`() {
        for (r in listOf(null, 30, 300)) {
            val m = Api.explain(429, r)
            // "spróbuj ponownie" jest w porzadku; chodzi o liczenie nieudanych prob.
            assertTrue(m, !m.contains("dużo prób") && !m.contains("błędn"))
        }
    }

    @Test
    fun `awaria serwera nie brzmi jak wina uzytkownika`() {
        assertTrue(Api.explain(500, null).contains("Serwer"))
        assertTrue(Api.explain(503, null).contains("Serwer"))
    }

    @Test
    fun `wygasla sesja prosi o ponowne logowanie`() {
        assertTrue(Api.explain(401, null).contains("Zaloguj"))
    }

    @Test
    fun `nieznany blad ma zapasowa tresc`() {
        assertTrue(Api.explain(418, null).isNotBlank())
    }
}
