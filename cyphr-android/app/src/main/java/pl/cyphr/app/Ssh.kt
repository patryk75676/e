package pl.cyphr.app

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Prawdziwa powloka po SSH. To nie jest atrapa: laczy sie z serwerem,
 * trzyma otwarty kanal shell i strumieniuje wyjscie na zywo.
 *
 * Klucz hosta jest zapamietywany przy pierwszym polaczeniu i przy kazdym kolejnym
 * sprawdzany. Zmiana klucza przerywa polaczenie, bo to typowy objaw podsluchu.
 */
class SshSession(
    private val scope: CoroutineScope,
    private val onOutput: (String) -> Unit,
    private val onClosed: (String?) -> Unit,
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var output: OutputStream? = null

    val connected: Boolean get() = session?.isConnected == true && channel?.isConnected == true

    suspend fun connect(host: String, port: Int, user: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val jsch = JSch()
                val s = jsch.getSession(user, host, port)
                s.setPassword(password)
                s.setConfig("StrictHostKeyChecking", "no") // sprawdzamy odcisk sami, nizej
                s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
                s.connect(15000)

                val key = s.hostKey?.key?.let { fingerprint(it) }
                val known = SecureStore.get("ssh_hostkey_$host")
                if (known != null && key != null && known != key) {
                    s.disconnect()
                    return@withContext Result.failure(
                        IllegalStateException("Klucz serwera się zmienił. Połączenie przerwane."),
                    )
                }
                if (known == null && key != null) SecureStore.put("ssh_hostkey_$host", key)

                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("xterm")
                output = ch.outputStream
                val input = ch.inputStream
                ch.connect(10000)

                session = s
                channel = ch
                pump(input)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun pump(input: InputStream) {
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val text = String(buffer, 0, read)
                    withContext(Dispatchers.Main) { onOutput(clean(text)) }
                }
                withContext(Dispatchers.Main) { onClosed(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onClosed(e.message) }
            }
        }
    }

    fun send(line: String) {
        scope.launch(Dispatchers.IO) {
            try {
                output?.write((line + "\n").toByteArray())
                output?.flush()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onClosed(e.message) }
            }
        }
    }

    fun disconnect() {
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null
        session = null
        output = null
    }

    private fun fingerprint(key: String): String {
        val raw = android.util.Base64.decode(key, android.util.Base64.DEFAULT)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    /** Usuwa sekwencje sterujace terminala, zeby tekst nadawal sie do wyswietlenia. */
    private fun clean(text: String): String = text
        .replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
        .replace(Regex("\u001B\\][^\u0007]*\u0007"), "")
        .replace("\r", "")
}
