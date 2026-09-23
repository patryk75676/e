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
    private val dir = File(System.getProperty("java.io.tmpdir"))

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
