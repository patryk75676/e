package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U dostawcy kazdy z tych modeli ma blizniaka o tej samej nazwie bez dopiska
 * "Uncensored". Skrocona nazwa w aplikacji sprawia, ze nie wiadomo, ktory jest
 * ktory — te testy pilnuja, zeby nazwa i identyfikator sie zgadzaly.
 */
class CatalogTest {

    @Test
    fun `katalog ma dokladnie zamowione modele`() {
        assertEquals(
            listOf("qwen3.8-27b-uncensored", "glm-5.3-flash-uncensored"),
            CATALOG.map { it.id },
        )
    }

    @Test
    fun `kazdy model jest wersja bez cenzury`() {
        CATALOG.forEach { assertTrue(it.id, it.id.endsWith("-uncensored")) }
    }

    @Test
    fun `nazwa mowi wprost ze to wersja bez cenzury`() {
        CATALOG.forEach { assertTrue(it.name, it.name.contains("Uncensored")) }
    }

    @Test
    fun `nazwa zgadza sie z identyfikatorem`() {
        CATALOG.forEach { a ->
            val zNazwy = a.name.lowercase().replace(" ", "-")
            assertTrue("${a.name} vs ${a.id}", zNazwy.endsWith(a.id.substringAfterLast("-")))
        }
    }

    @Test
    fun `nie ma dwoch pozycji o tym samym id`() {
        assertEquals(CATALOG.size, CATALOG.map { it.id }.toSet().size)
    }
}
