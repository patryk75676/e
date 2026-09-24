package pl.cyphr.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jezyk aplikacji: wybor po kraju, teksty parami, liczby i bledy serwera po angielsku. */
class LanguageTest {

    @After
    fun back() = Lang.set(Lang.PL)

    private val polish = Regex("[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ„”]")

    @Test
    fun `kraj sieci decyduje - polski tylko dla Polski`() {
        assertEquals(Lang.PL, LangPick.fromCountry("pl", "en"))
        assertEquals(Lang.PL, LangPick.fromCountry("PL", "de"))
        assertEquals(Lang.EN, LangPick.fromCountry("de", "pl"))
        assertEquals(Lang.EN, LangPick.fromCountry("us", "en"))
        assertEquals(Lang.EN, LangPick.fromCountry("gb", null))
    }

    @Test
    fun `bez kraju sieci zostaje jezyk telefonu`() {
        assertEquals(Lang.PL, LangPick.fromCountry(null, "pl"))
        assertEquals(Lang.PL, LangPick.fromCountry("", "pl"))
        assertEquals(Lang.EN, LangPick.fromCountry(null, "en"))
        assertEquals(Lang.EN, LangPick.fromCountry(null, "uk"))
        assertEquals(Lang.EN, LangPick.fromCountry(null, null))
    }

    @Test
    fun `kod jezyka z serwera`() {
        assertEquals(Lang.PL, Lang.of("pl"))
        assertEquals(Lang.EN, Lang.of(" EN "))
        assertNull(Lang.of("de"))
        assertNull(Lang.of(null))
    }

    @Test
    fun `teksty parami`() {
        assertEquals("Zapisz", tr("Zapisz", "Save"))
        Lang.set(Lang.EN)
        assertEquals("Save", tr("Zapisz", "Save"))
        assertTrue(isEn)
    }

    @Test
    fun `liczby z rzeczownikiem w obu jezykach`() {
        assertEquals("1 strona", pagesLabel(1))
        assertEquals("3 strony", pagesLabel(3))
        assertEquals("5 stron", pagesLabel(5))
        assertEquals("12 stron", pagesLabel(12))
        assertEquals("22 strony", pagesLabel(22))
        Lang.set(Lang.EN)
        assertEquals("1 page", pagesLabel(1))
        assertEquals("3 pages", pagesLabel(3))
        assertEquals("12 pages", pagesLabel(12))
    }

    @Test
    fun `kwoty i liczby po angielsku z kropka`() {
        assertEquals("1 234,50 USD", usd(1234.5).replace(' ', ' ').replace(' ', ' '))
        Lang.set(Lang.EN)
        assertEquals("1,234.50 USD", usd(1234.5))
        assertEquals("12,345", int(12345))
    }

    @Test
    fun `bledy serwera po angielsku bez polskiego tekstu z serwera`() {
        val fromServer = "Nieprawidłowy e-mail lub hasło."
        assertEquals(fromServer, Api.errorMessage("bad_credentials", fromServer, 401, null))
        Lang.set(Lang.EN)
        assertEquals("Wrong email or password.", Api.errorMessage("bad_credentials", fromServer, 401, null))
        // Nieznany kod: tekst z samego statusu, po angielsku.
        val unknown = Api.errorMessage("cos_nowego", "Coś nowego po polsku.", 503, null)
        assertFalse(unknown, polish.containsMatchIn(unknown))
        assertEquals("Too many requests in a row. Wait 5 min and try again.", Api.errorMessage("rate_limited", "Za dużo prób.", 429, 300))
    }

    @Test
    fun `kazdy status ma angielski opis`() {
        Lang.set(Lang.EN)
        for (status in listOf(0, 400, 401, 402, 403, 404, 408, 429, 500, 502, 503, 504)) {
            val m = Api.explain(status, 30)
            assertFalse("$status: $m", polish.containsMatchIn(m))
        }
    }

    @Test
    fun `instrukcje dla modelu po angielsku`() {
        Lang.set(Lang.EN)
        val identity = Persona.system("CYPHR Flash")
        assertTrue(identity.contains("CYPHR Flash") && identity.contains("CYPHR model"))
        val tools = AgentTools.instructions("the Android shell")
        assertTrue(tools.contains("!RUN: <command>"))
        val images = ImageTools.instructions(2, 2)
        assertTrue(images.contains("!IMAGE:") && images.contains("--ar"))
        for (text in listOf(identity, tools, images, ImageTools.instructions(0, 2))) {
            assertFalse(text, polish.containsMatchIn(text))
        }
    }

    @Test
    fun `domyslna instrukcja w jezyku aplikacji i nie jest zapisywana jako wlasna`() {
        assertTrue(Prefs.isDefaultPrompt(Prefs.DEFAULT_PROMPT_PL))
        assertTrue(Prefs.isDefaultPrompt("  " + Prefs.DEFAULT_PROMPT_EN + "\n"))
        assertFalse(Prefs.isDefaultPrompt("Odpowiadaj jak pirat."))
        assertEquals(Prefs.DEFAULT_PROMPT_PL, Prefs.defaultPrompt)
        Lang.set(Lang.EN)
        assertEquals(Prefs.DEFAULT_PROMPT_EN, Prefs.defaultPrompt)
        assertFalse(polish.containsMatchIn(Prefs.DEFAULT_PROMPT_EN))
    }

    @Test
    fun `katalog modeli po angielsku`() {
        Lang.set(Lang.EN)
        assertEquals(listOf("CYPHR Flash", "CYPHR Pro"), CATALOG.map { it.name })
        CATALOG.forEach { assertFalse(it.description, polish.containsMatchIn(it.description)) }
        assertEquals("New chat", Chat().label)
    }
}
