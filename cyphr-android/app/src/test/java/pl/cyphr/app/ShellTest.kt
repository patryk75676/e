package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Polecenia zlecane przez model nie moga zawiesic czatu. Na telefonie powloka to
 * /system/bin/sh — w testach ta sama logika na /bin/sh komputera.
 */
class ShellTest {
    private val dir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")

    /** Czy w systemie zyje proces z dokladnie taka linia polecen. */
    private fun alive(cmdline: String): Boolean =
        File("/proc").listFiles().orEmpty().any { d ->
            d.name.all { it.isDigit() } && try {
                File(d, "cmdline").readText().replace('\u0000', ' ').trim() == cmdline
            } catch (e: Exception) {
                false
            }
        }

    private fun run(cmd: String, timeout: Long = 5, max: Int = 64_000) =
        Shell.run(cmd, dir, timeout, max, shell = "/bin/sh")

    @Test
    fun `zwykle polecenie zwraca wynik i kod wyjscia`() {
        val r = run("echo hej; exit 3")
        assertEquals("hej\n", r.output)
        assertEquals(3, r.exitCode)
        assertFalse(r.timedOut)
    }

    @Test
    fun `polecenie bez konca jest przerywane po limicie`() {
        val start = System.currentTimeMillis()
        val r = run("echo start; sleep 30", timeout = 1)
        val took = System.currentTimeMillis() - start
        assertTrue(r.timedOut)
        assertTrue("trwalo $took ms", took < 8_000)
        assertTrue(r.output.startsWith("start"))
    }

    @Test
    fun `przerwanie zabija tez procesy potomne`() {
        // Samo zabicie powloki zostawialo jej dzieci — np. ping zlecony przez model
        // dzialal dalej w tle po przerwaniu.
        val r = run("sleep 37 & sleep 38; echo nigdy", timeout = 1)
        assertTrue(r.timedOut)
        Thread.sleep(300)
        assertFalse("sleep 37 dalej zyje", alive("sleep 37"))
        assertFalse("sleep 38 dalej zyje", alive("sleep 38"))
    }

    @Test
    fun `przerwanie z zewnatrz zabija drzewo procesow`() {
        val running = Shell.start("sleep 39 | cat", dir, shell = "/bin/sh")
        Thread.sleep(400)
        assertTrue("sleep 39 powinien dzialac", alive("sleep 39"))
        running.kill()
        Thread.sleep(300)
        assertFalse("sleep 39 dalej zyje", alive("sleep 39"))
    }

    @Test
    fun `cat bez argumentow nie czeka na wejscie`() {
        val r = run("cat; echo koniec", timeout = 3)
        assertFalse(r.timedOut)
        assertEquals("koniec\n", r.output)
    }

    @Test
    fun `wielki wynik jest przycinany zamiast zapychac pamiec`() {
        val r = run("yes | head -c 200000", max = 1000)
        assertEquals(1000, r.output.length)
        assertTrue(r.truncated)
    }

    @Test
    fun `bledy trafiaja do wyniku`() {
        val r = run("ls /na/pewno/nie/ma/takiego/katalogu")
        assertTrue(r.output.isNotBlank())
        assertTrue(r.exitCode != 0)
    }
}
