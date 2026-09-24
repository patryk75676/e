package pl.cyphr.app

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Polecenia powloki z twardym limitem czasu i rozmiaru wyniku.
 *
 * Bez limitu polecenie, ktore sie nie konczy (ping bez -c, top, cat czekajacy
 * na wejscie), wieszalo odpowiedz modelu na zawsze — czat stal na „mysli…”.
 * Wejscie jest od razu zamykane, bo polecenie i tak nie ma skad go dostac.
 */
object Shell {
    data class Result(val output: String, val exitCode: Int, val timedOut: Boolean, val truncated: Boolean)

    /**
     * Uruchomiona powloka. Przerwanie zabija cale drzewo procesow: samo zabicie `sh`
     * zostawialo przy zyciu jego dzieci — `ping` albo `yes > /dev/null` przerwane po
     * limicie dalej dzialaly w tle, az Android nie zabil calej aplikacji.
     */
    class Running internal constructor(
        val process: Process,
        private val pidFile: File,
        private val shell: String,
    ) {
        fun kill() {
            val root = try { pidFile.readText().trim().toIntOrNull() } catch (e: Exception) { null }
            if (root != null) {
                val victims = descendants(root) + root
                try {
                    ProcessBuilder(shell, "-c", "kill -9 ${victims.joinToString(" ")} 2>/dev/null")
                        .start().waitFor(2, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
            }
            process.destroyForcibly()
            cleanup()
        }

        fun cleanup() {
            pidFile.delete()
        }
    }

    /**
     * Startuje polecenie. Pierwsza linia skryptu zapisuje PID powloki do pliku — Android
     * nie daje go z obiektu Process, a bez niego nie da sie znalezc procesow potomnych.
     */
    fun start(
        command: String,
        dir: File,
        shell: String = "/system/bin/sh",
        // Osobno od katalogu roboczego — `ls` w katalogu domowym nie ma pokazywac tego pliku.
        pidDir: File = File(System.getProperty("java.io.tmpdir") ?: dir.path),
    ): Running {
        pidDir.mkdirs()
        val pidFile = File.createTempFile("cyphr-pid", ".txt", pidDir)
        val script = "echo \$\$ > '${pidFile.absolutePath}'\n$command"
        val process = ProcessBuilder(shell, "-c", script)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        try { process.outputStream.close() } catch (_: Exception) {}
        return Running(process, pidFile, shell)
    }

    fun run(
        command: String,
        dir: File,
        timeoutSeconds: Long,
        maxChars: Int = 64_000,
        shell: String = "/system/bin/sh",
        pidDir: File = File(System.getProperty("java.io.tmpdir") ?: dir.path),
    ): Result {
        val running = start(command, dir, shell, pidDir)
        val process = running.process

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
            running.kill()
            process.waitFor(2, TimeUnit.SECONDS)
        } else {
            running.cleanup()
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

    /** Wszyscy potomkowie procesu [root], wedlug ppid z /proc/<pid>/stat. */
    internal fun descendants(root: Int): List<Int> {
        val children = mutableMapOf<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val stat = try { File(d, "stat").readText() } catch (e: Exception) { return@forEach }
            // Format: pid (nazwa) stan ppid ... — nazwa moze miec spacje i nawiasy.
            val ppid = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()
                ?: return@forEach
            children.getOrPut(ppid) { mutableListOf() } += pid
        }
        val out = mutableListOf<Int>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            children[parent]?.forEach { out += it; queue += it }
        }
        return out
    }
}
