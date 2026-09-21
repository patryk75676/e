package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U dostawcy prawie kazdy z tych modeli ma blizniaka o tej samej nazwie bez
 * dopiska. Skrocona nazwa sprawia, ze nie wiadomo, ktory jest ktory — a model
 * spoza listy serwera konczy sie bledem przy pierwszej wiadomosci. Te testy
 * pilnuja jednego i drugiego.
 */
class CatalogTest {

    /** Rodziny modeli bez cenzury, jakie wystawia dostawca. */
    private val bezCenzury = Regex("uncensored|derestricted|heretic|abliterated")

    @Test
    fun `katalog ma dokladnie zamowione modele`() {
        assertEquals(
            listOf(
                "abliterated-model-large-v2",
                "glm-5.3-flash-uncensored",
                "qwen3.8-27b-uncensored",
                "qwen3.5-27b-claude-4.6-opus-reasoning-distilled-derestricted",
                "gemma-4-31b-sdft-heretic-rp",
            ),
            CATALOG.map { it.id },
        )
    }

    @Test
    fun `kazdy model jest wersja bez cenzury`() {
        CATALOG.forEach { assertTrue(it.id, bezCenzury.containsMatchIn(it.id)) }
    }

    @Test
    fun `nazwa mowi wprost ktora to wersja`() {
        CATALOG.forEach { assertTrue(it.name, bezCenzury.containsMatchIn(it.name.lowercase())) }
    }

    @Test
    fun `kazda pozycja ma nazwe i opis`() {
        CATALOG.forEach {
            assertTrue(it.id, it.name.isNotBlank())
            assertTrue(it.id, it.description.isNotBlank())
        }
    }

    @Test
    fun `nie ma dwoch pozycji o tym samym id`() {
        assertEquals(CATALOG.size, CATALOG.map { it.id }.toSet().size)
    }

    @Test
    fun `model domyslny jest w katalogu`() {
        assertTrue(CATALOG.any { it.id == DEFAULT_AGENT })
    }

    @Test
    fun `model domyslny nie jest tym najdrozszym`() {
        assertTrue(CATALOG.first().id != DEFAULT_AGENT)
    }

    // ---------- laczenie z lista serwera ----------

    @Test
    fun `pokazujemy tylko to co dopuszcza serwer`() {
        val zSerwera = listOf(Agent("qwen3.8-27b-uncensored", "Qwen3.8 27B", "0,38 / 2,25 $ za mln tokenów"))
        assertEquals(listOf("qwen3.8-27b-uncensored"), mergeAgents(zSerwera).map { it.id })
    }

    @Test
    fun `nazwa idzie z katalogu a cena z serwera`() {
        val zSerwera = listOf(Agent("qwen3.8-27b-uncensored", "Qwen3.8 27B", "0,38 / 2,25 $ za mln tokenów"))
        val a = mergeAgents(zSerwera).single()
        assertEquals("Qwen3.8 27B Uncensored", a.name)
        assertTrue(a.description, a.description.contains("131 tys. kontekstu"))
        assertTrue(a.description, a.description.contains("2,25"))
    }

    @Test
    fun `model spoza katalogu przechodzi bez zmian`() {
        val obcy = Agent("jakis-inny-model", "Jakis Inny", "opis")
        assertEquals(obcy, mergeAgents(listOf(obcy)).single())
    }

    @Test
    fun `kolejnosc jest z katalogu a nie z serwera`() {
        val zSerwera = listOf(
            Agent("gemma-4-31b-sdft-heretic-rp", "x", ""),
            Agent("abliterated-model-large-v2", "x", ""),
        )
        assertEquals(
            listOf("abliterated-model-large-v2", "gemma-4-31b-sdft-heretic-rp"),
            mergeAgents(zSerwera).map { it.id },
        )
    }

    @Test
    fun `gdy serwer milczy zostaje caly katalog`() {
        assertEquals(CATALOG, mergeAgents(emptyList()))
    }

    @Test
    fun `pusty opis z serwera nie dokleja separatora`() {
        val zSerwera = listOf(Agent("qwen3.8-27b-uncensored", "x", ""))
        assertEquals("131 tys. kontekstu", mergeAgents(zSerwera).single().description)
    }
}
