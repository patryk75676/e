package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dokladnie dwa modele wskazane przez wlasciciela, widoczne wylacznie jako
 * modele CYPHR. Identyfikator dostawcy idzie tylko do serwera — na ekranie
 * nie ma ani jego, ani nazwy dostawcy. Model spoza listy serwera konczy sie
 * bledem przy pierwszej wiadomosci, wiec tez go nie pokazujemy.
 */
class CatalogTest {
    private val origin = listOf("qwen", "glm", "alibaba", "zhipu", "routeway", "uncensored", "27b")

    @Test
    fun `katalog ma dokladnie te dwa modele i zadnego wiecej`() {
        assertEquals(
            listOf("glm-5.3-flash-uncensored", "qwen3.8-27b-uncensored"),
            CATALOG.map { it.id },
        )
    }

    @Test
    fun `kazdy model jest wersja bez cenzury`() {
        CATALOG.forEach { assertTrue(it.id, it.id.endsWith("-uncensored")) }
    }

    @Test
    fun `nazwy to modele CYPHR`() {
        assertEquals(listOf("CYPHR Flash", "CYPHR Pro"), CATALOG.map { it.name })
    }

    @Test
    fun `nazwa i opis nie zdradzaja pochodzenia`() {
        CATALOG.forEach { a ->
            val visible = "${a.name} ${a.description}".lowercase()
            origin.forEach { assertFalse("${a.id}: $it", visible.contains(it)) }
        }
    }

    @Test
    fun `kazda pozycja ma nazwe i opis`() {
        CATALOG.forEach {
            assertTrue(it.id, it.name.isNotBlank())
            assertTrue(it.id, it.description.isNotBlank())
        }
    }

    @Test
    fun `nie ma dwoch pozycji o tym samym id ani nazwie`() {
        assertEquals(CATALOG.size, CATALOG.map { it.id }.toSet().size)
        assertEquals(CATALOG.size, CATALOG.map { it.name }.toSet().size)
    }

    @Test
    fun `model domyslny jest w katalogu`() {
        assertTrue(CATALOG.any { it.id == DEFAULT_AGENT })
    }

    @Test
    fun `model domyslny to ten z dluzsza pamiecia`() {
        assertEquals("glm-5.3-flash-uncensored", DEFAULT_AGENT)
    }

    // ---------- laczenie z lista serwera ----------

    @Test
    fun `pokazujemy tylko to co dopuszcza serwer`() {
        val zSerwera = listOf(Agent("qwen3.8-27b-uncensored", "Qwen3.8 27B", "", "0,38 / 2,25 $ za mln tokenów"))
        assertEquals(listOf("qwen3.8-27b-uncensored"), mergeAgents(zSerwera).map { it.id })
    }

    @Test
    fun `nazwa i opis ida z katalogu a cena z serwera`() {
        val zSerwera = listOf(Agent("qwen3.8-27b-uncensored", "Qwen3.8 27B", "alibaba", "0,38 / 2,25 $ za mln tokenów"))
        val a = mergeAgents(zSerwera).single()
        assertEquals("CYPHR Pro", a.name)
        assertEquals(CATALOG[1].description, a.description)
        assertEquals("0,38 / 2,25 $ za mln tokenów", a.price)
    }

    @Test
    fun `bez ceny z serwera nie ma ceny`() {
        val zSerwera = listOf(Agent("glm-5.3-flash-uncensored", "GLM", ""))
        assertNull(mergeAgents(zSerwera).single().price)
    }

    @Test
    fun `model spoza katalogu nie trafia na liste`() {
        val zSerwera = listOf(
            Agent("jakis-inny-model", "Jakis Inny", "", "1 $"),
            Agent("glm-5.3-flash-uncensored", "GLM", ""),
        )
        assertEquals(listOf("glm-5.3-flash-uncensored"), mergeAgents(zSerwera).map { it.id })
    }

    @Test
    fun `kolejnosc jest z katalogu a nie z serwera`() {
        val zSerwera = listOf(
            Agent("qwen3.8-27b-uncensored", "x", ""),
            Agent("glm-5.3-flash-uncensored", "x", ""),
        )
        assertEquals(
            listOf("glm-5.3-flash-uncensored", "qwen3.8-27b-uncensored"),
            mergeAgents(zSerwera).map { it.id },
        )
    }

    @Test
    fun `gdy serwer milczy zostaje caly katalog`() {
        assertEquals(CATALOG, mergeAgents(emptyList()))
    }

    @Test
    fun `gdy serwer nie zna naszych modeli zostaje caly katalog`() {
        assertEquals(CATALOG, mergeAgents(listOf(Agent("jakis-inny-model", "Jakis Inny", ""))))
    }
}
