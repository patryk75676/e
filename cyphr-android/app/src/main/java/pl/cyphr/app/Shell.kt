package pl.cyphr.app

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Jednorazowe polecenie powloki z twardym limitem czasu i rozmiaru wyniku.
 *
 * Bez limitu polecenie, ktore sie nie konczy (ping bez -c, top, cat czekajacy
 * na wejscie), wieszalo odpowiedz modelu na zawsze — czat stal na „mysli…”.
 * Wejscie jest od razu zamykane, bo polecenie i tak nie ma skad go dostac.
 */
object Shell {
    data class Result(val output: String, val exitCode: Int, val timedOut: Boolean, val truncated: Boolean)

    fun run(
        command: String,
        dir: File,
        timeoutSeconds: Long,
        maxChars: Int = 64_000,
        shell: String = "/system/bin/sh",
    ): Result {
        val process = ProcessBuilder(shell, "-c", command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        try { process.outputStream.close() } catch (_: Exception) {}

        val out = StringBuilder()
        var truncated = false
        // Wynik czytamy w osobnym watku: pelny bufor potoku zatrzymalby proces,
        // a my w tym czasie czekalibysmy na jego koniec.
        val reader = thread(isDaemon = true, name = "cyphr-shell") {
            try {
                process.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) {
                            val room = maxChars - out.length
                            if (room > 0) out.append(buf, 0, minOf(n, room))
                            if (n > room) truncated = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        reader.join(2000)
        val text = synchronized(out) { out.toString() }
        return Result(
            output = text,
            exitCode = if (finished) process.exitValue() else -1,
            timedOut = !finished,
            truncated = truncated,
        )
    }
}
