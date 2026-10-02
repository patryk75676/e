package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Logika mostu do Termuxa, ktora da sie sprawdzic bez telefonu. */
class TermuxTest {

    // ---------- wersja ----------

    @Test
    fun `wyniki przez PendingIntent sa od 0_109`() {
        assertEquals(true, Termux.supportsResults("0.118.1", 118))
        assertEquals(true, Termux.supportsResults("0.109", 109))
        assertEquals(false, Termux.supportsResults("0.101", 101))
        assertEquals(false, Termux.supportsResults("0.75", 75))
    }

    @Test
    fun `nieznany format wersji nie blokuje - rozstrzygnie prawdziwe sprawdzenie`() {
        assertNull(Termux.supportsResults("googleplay.2024.01.12", 1_000_012))
        assertNull(Termux.supportsResults(null, 0))
    }

    // ---------- wynik od Termuxa ----------

    @Test
    fun `err rowne -1 to sukces`() {
        val r = Termux.parseResult("ok\n", "", 0, -1, null, "3", "0")
        assertNull(r.failure)
        assertEquals("ok\n", r.stdout)
        assertFalse(r.truncated)
    }

    @Test
    fun `blad Termuxa o allow-external-apps jest rozpoznany`() {
        val msg = "The com.termux.RUN_COMMAND intent is not allowed because the " +
            "allow-external-apps property is not set to true in termux.properties."
        val r = Termux.parseResult(null, null, null, 1, msg, null, null)
        assertEquals(Termux.Failure.ExternalAppsBlocked, r.failure)
        assertEquals(msg, r.errMsg)
    }

    @Test
    fun `inny blad Termuxa niesie jego wlasny komunikat`() {
        val r = Termux.parseResult("", "", -1, 2, "Invalid executable path", null, null)
        assertEquals(Termux.Failure.TermuxError, r.failure)
        assertEquals("Invalid executable path", r.errMsg)
    }

    @Test
    fun `obciety wynik jest oznaczony`() {
        val r = Termux.parseResult("ogon", "", 0, -1, null, "250000", "0")
        assertTrue(r.truncated)
    }

    @Test
    fun `brak pola err - starszy Termux - to nie blad`() {
        val r = Termux.parseResult("x", "", 0, null, null, null, null)
        assertNull(r.failure)
    }

    // ---------- diagnoza ----------

    private fun ok(out: String = Termux.MARKER) = Termux.Result(out, "", 0)

    @Test
    fun `diagnoza idzie od najprostszej przyczyny`() {
        assertEquals(Termux.State.NotInstalled, Termux.diagnose(false, true, true, ok()))
        assertEquals(Termux.State.TooOld, Termux.diagnose(true, false, true, ok()))
        assertEquals(Termux.State.NoPermission, Termux.diagnose(true, true, false, ok()))
        assertEquals(Termux.State.Ready, Termux.diagnose(true, null, true, ok()))
    }

    @Test
    fun `Termux z Google Play bez zgody RUN_COMMAND to nie odmowa, tylko inna wersja`() {
        // Brak zgody w systemie nie moze konczyc sie „odrzucono na stale” — tej zgody tam po prostu nie ma.
        assertEquals(
            Termux.State.NoCommandApi,
            Termux.diagnose(true, null, permission = false, probe = null, commandApi = false),
        )
        // Niezainstalowany dalej ma pierwszenstwo.
        assertEquals(
            Termux.State.NotInstalled,
            Termux.diagnose(false, null, permission = false, probe = null, commandApi = false),
        )
        assertTrue(Termux.isPlayBuild("googleplay.2026.06.21"))
        assertFalse(Termux.isPlayBuild("0.118.3"))
        assertFalse(Termux.isPlayBuild(null))
        // Wersja z Play nie jest brana za „za stara” — rozstrzyga brak zgody, nie numer.
        assertNull(Termux.supportsResults("googleplay.2026.06.21", 1_000))
    }

    @Test
    fun `diagnoza rozroznia blokade, cisze i odmowe startu`() {
        fun fail(f: Termux.Failure) = Termux.Result("", "", -1, failure = f)
        assertEquals(Termux.State.ExternalAppsBlocked, Termux.diagnose(true, true, true, fail(Termux.Failure.ExternalAppsBlocked)))
        assertEquals(Termux.State.NoReply, Termux.diagnose(true, true, true, fail(Termux.Failure.NoReply)))
        assertEquals(Termux.State.NoPermission, Termux.diagnose(true, true, true, fail(Termux.Failure.NoPermission)))
        assertEquals(Termux.State.Failed, Termux.diagnose(true, true, true, fail(Termux.Failure.TermuxError)))
        assertEquals(Termux.State.Failed, Termux.diagnose(true, true, true, ok(out = "cos innego")))
    }

    // ---------- polecenie konfigurujace ----------

    /**
     * Polecenie, ktore uzytkownik wkleja w Termuxie. Musi zadzialac przy pustym pliku,
     * przy wpisie `false` i przy wklejeniu dwa razy — bez dublowania wpisu.
     */
    @Test
    fun `polecenie konfigurujace ustawia allow-external-apps raz i nie psuje reszty`() {
        val home = createTempDir(prefix = "termux-home")
        try {
            val props = File(home, ".termux/termux.properties")
            props.parentFile.mkdirs()
            props.writeText("# allow-external-apps = true\nextra-keys = [['ESC']]\nallow-external-apps=false\n")
            repeat(2) {
                val p = ProcessBuilder("/bin/sh", "-c", Termux.SETUP_PROPERTIES)
                    .apply { environment()["HOME"] = home.path }
                    .redirectErrorStream(true).start()
                assertEquals(p.inputStream.bufferedReader().readText(), 0, p.waitFor())
            }
            val lines = props.readLines()
            assertEquals(1, lines.count { it.trim().startsWith("allow-external-apps") })
            assertTrue(lines.contains("allow-external-apps = true"))
            assertTrue("komentarz zostaje", lines.contains("# allow-external-apps = true"))
            assertTrue("inne ustawienia zostaja", lines.contains("extra-keys = [['ESC']]"))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `polecenie konfigurujace dziala tez bez istniejacego pliku`() {
        val home = createTempDir(prefix = "termux-home")
        try {
            val p = ProcessBuilder("/bin/sh", "-c", Termux.SETUP_PROPERTIES)
                .apply { environment()["HOME"] = home.path }
                .redirectErrorStream(true).start()
            assertEquals(0, p.waitFor())
            assertEquals(listOf("allow-external-apps = true"), File(home, ".termux/termux.properties").readLines())
        } finally {
            home.deleteRecursively()
        }
    }

    // ---------- katalog w terminalu ----------

    @Test
    fun `zwykle cd daje sciezke do sprawdzenia w Termuksie`() {
        assertEquals(Termux.HOME, Termux.cdTarget(""))
        assertEquals(Termux.HOME, Termux.cdTarget("~"))
        assertEquals(Termux.HOME + "/projekt", Termux.cdTarget("~/projekt"))
        assertEquals("..", Termux.cdTarget(".."))
        assertEquals("/sdcard/Download", Termux.cdTarget("/sdcard/Download"))
        assertEquals("Moje pliki", Termux.cdTarget("\"Moje pliki\""))
        assertEquals("Moje pliki", Termux.cdTarget("'Moje pliki'"))
    }

    @Test
    fun `cd ze znakami powloki nie omija okna zgody`() {
        // Takie polecenie idzie zwykla droga, z ocena ryzyka — nie jako „samo cd”.
        listOf("x; rm -rf ~", "x && rm plik", "x | sh", "\$(rm plik)", "`rm plik`", "x > plik", "a\nrm b")
            .forEach { assertNull("przepuscilo: $it", Termux.cdTarget(it)) }
    }

    @Test
    fun `sciezka jest bezpiecznie cytowana dla basha`() {
        assertEquals("'/a/b'", Termux.quote("/a/b"))
        assertEquals("'it'\\''s'", Termux.quote("it's"))
        // Cytowanie sprawdzone prawdziwa powloka: nic sie nie wykonuje, sciezka wraca doslownie.
        val weird = "a'b \$(echo X) `id` ; c"
        val p = ProcessBuilder("/bin/sh", "-c", "printf %s " + Termux.quote(weird)).start()
        assertEquals(weird, p.inputStream.bufferedReader().readText())
    }

    @Test
    fun `sciezka w znaku zachety skraca katalog domowy do tyldy`() {
        assertEquals("~", Termux.shortPath(Termux.HOME))
        assertEquals("~/projekt", Termux.shortPath(Termux.HOME + "/projekt"))
        assertEquals("/sdcard", Termux.shortPath("/sdcard"))
    }
}
