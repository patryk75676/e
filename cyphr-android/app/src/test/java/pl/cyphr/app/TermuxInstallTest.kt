package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Termux jednym przyciskiem: wersja z F-Droid, sprawdzenie pliku i opis postepu. */
class TermuxInstallTest {
    @Test
    fun `wersja polecana przez F-Droid`() {
        val json = """{"packageName":"com.termux","suggestedVersionCode":1002,"packages":[
            {"versionName":"0.119.0-beta.3","versionCode":1022},{"versionName":"0.118.3","versionCode":1002}]}"""
        assertEquals(1002, TermuxInstall.pickVersion(json))
    }

    @Test
    fun `bez polecanej najnowsza wersja bez bety`() {
        val json = """{"packages":[{"versionName":"0.119.0-beta.3","versionCode":1022},
            {"versionName":"0.118.3","versionCode":1002},{"versionName":"0.118.1","versionCode":1000}]}"""
        assertEquals(1002, TermuxInstall.pickVersion(json))
    }

    @Test
    fun `zla odpowiedz to brak wersji`() {
        assertNull(TermuxInstall.pickVersion("<html>502</html>"))
        assertNull(TermuxInstall.pickVersion("""{"packages":[]}"""))
        assertNull(TermuxInstall.pickVersion("""{"packages":[{"versionName":"1.0-beta","versionCode":5}]}"""))
    }

    @Test
    fun `przypiety odcisk to certyfikat, ktorym F-Droid podpisuje Termuksa`() {
        // Ten sam certyfikat co w com.termux_1002.apk z f-droid.org (apksigner: 228fb2cf…1c42).
        val cert = javaClass.classLoader!!.getResourceAsStream("fdroid-termux.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        assertTrue(cert.subjectX500Principal.name.contains("O=fdroid.org"))
        // Signature.toByteArray() w Androidzie to ten sam zapis DER certyfikatu.
        assertEquals(TermuxInstall.FDROID_SIGNER, TermuxInstall.sha256(cert.encoded))
    }

    @Test
    fun `instalator dostaje tylko oryginalnego Termuksa`() {
        val fdroid = TermuxInstall.FDROID_SIGNER
        assertTrue(TermuxInstall.genuine("com.termux", listOf(fdroid)))
        assertTrue(TermuxInstall.genuine("com.termux", listOf(fdroid.uppercase())))
        assertFalse(TermuxInstall.genuine("com.termux", listOf("00".repeat(32))))
        assertFalse(TermuxInstall.genuine("com.termux", listOf(fdroid, "00".repeat(32))))
        assertFalse(TermuxInstall.genuine("com.termux.evil", listOf(fdroid)))
        assertFalse(TermuxInstall.genuine("pl.cyphr.app", listOf(fdroid)))
        assertFalse(TermuxInstall.genuine("com.termux", emptyList()))
        assertFalse(TermuxInstall.genuine(null, listOf(fdroid)))
    }

    @Test
    fun `postep po ludzku`() {
        assertEquals("34 z 109 MB", TermuxInstall.progressLabel(34L * 1048576, 113_880_067))
        assertEquals("0 z 109 MB", TermuxInstall.progressLabel(0, 113_880_067))
        assertEquals("12 MB", TermuxInstall.progressLabel(12L * 1048576, -1))
    }

    @Test
    fun `Termux do wymiany rozpoznany po wersji z Play`() {
        assertTrue(Termux.isPlayBuild("googleplay.2026.06.21"))
        assertFalse(Termux.isPlayBuild("0.118.3"))
    }
}
