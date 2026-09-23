package pl.cyphr.app

/**
 * Czy polecenie moze skasowac, nadpisac albo wyslac dane. Takie pytaja zawsze —
 * tego nie wylacza zadne ustawienie.
 *
 * Polecenie dzielimy na czesci (`;`, `&&`, `||`, `|`, `&`, nowa linia, `$(...)`,
 * backticki, nawiasy) i sprawdzamy kazda z osobna. Wczesniej patrzylismy tylko
 * na poczatek linii, wiec `ls && rm -rf ~` przechodzil bez pytania. To zabezpieczenie
 * przed pomylka, nie piaskownica: wolimy zapytac o jedno polecenie za duzo niz przepuscic
 * groźne — cudzyslow ze srednikiem w srodku po prostu spyta.
 */
object CommandRisk {

    /** Polecenia, ktore same z siebie kasuja, nadpisuja, zabijaja albo wysylaja. */
    private val DESTRUCTIVE = setOf(
        "rm", "rmdir", "unlink", "mv", "cp", "ln", "install", "shred", "truncate", "wipe",
        "chmod", "chown", "chgrp", "dd", "mkfs", "fdisk", "parted", "mount", "umount",
        "kill", "pkill", "killall", "reboot", "shutdown", "poweroff", "halt",
        "curl", "wget", "tee", "scp", "rsync",
        "su", "sudo", "doas",
        "sh", "bash", "zsh", "dash", "mksh", "ash", "eval", "source",
        "pm", "am", "cmd", "settings", "setprop", "svc",
    )

    /** Nakladki, za ktorymi stoi dopiero wlasciwe polecenie. */
    private val WRAPPERS = setOf(
        "env", "nohup", "time", "nice", "ionice", "command", "builtin", "exec", "busybox", "toybox",
        "xargs", "timeout", "stdbuf", "watch", "then", "do", "else", "elif", "if", "while", "until", "!",
    )

    private val SEPARATORS = Regex("""\$\(|[;&|\n`(){}]""")
    private val ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")
    private val NUMBER = Regex("""^\d+(\.\d+)?[smhd]?$""")

    fun isRisky(command: String): Boolean {
        // Przekierowanie do pliku nadpisuje dane, gdziekolwiek stoi.
        if (command.contains('>')) return true
        return command.split(SEPARATORS).any { segmentIsRisky(it) }
    }

    private fun segmentIsRisky(segment: String): Boolean {
        val words = segment.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::bare)
        var i = 0
        // Pomijamy przypisania zmiennych i nakladki razem z ich opcjami.
        while (i < words.size) {
            val w = words[i]
            when {
                ASSIGNMENT.matches(w) -> i++
                w in WRAPPERS -> {
                    i++
                    while (i < words.size && (words[i].startsWith("-") || NUMBER.matches(words[i]) ||
                            ASSIGNMENT.matches(words[i]))
                    ) i++
                }
                else -> break
            }
        }
        val name = words.getOrNull(i) ?: return false
        val args = words.drop(i + 1)
        return when (name) {
            in DESTRUCTIVE -> true
            "sed" -> args.any { it == "-i" || it.startsWith("-i") || it.startsWith("--in-place") }
            "find" -> args.any { it in setOf("-delete", "-exec", "-execdir", "-ok", "-okdir") }
            "git" -> gitIsRisky(args)
            else -> false
        }
    }

    private fun gitIsRisky(args: List<String>): Boolean = when (args.firstOrNull()) {
        "rm", "clean" -> true
        "reset" -> "--hard" in args
        "push" -> args.any { it == "-f" || it.startsWith("--force") }
        else -> false
    }

    /** Slowo bez cudzyslowow, ukosnikow wstecznych i sciezki: `"/system/bin/r\m"` → `rm`. */
    private fun bare(word: String): String =
        word.filterNot { it == '"' || it == '\'' || it == '\\' }.substringAfterLast('/')
}
