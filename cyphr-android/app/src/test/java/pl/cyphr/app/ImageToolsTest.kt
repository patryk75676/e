package pl.cyphr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prosba modelu o obraz: wychwycenie, proporcje i to, co widzi uzytkownik. */
class ImageToolsTest {
    @Test
    fun `prosba z proporcjami`() {
        val r = ImageTools.requested("Proszę bardzo!\n!IMAGE: a red fox in the snow, cinematic --ar 16:9\n")
        assertEquals(ImageTools.Request("a red fox in the snow, cinematic", "16:9"), r)
    }

    @Test
    fun `bez proporcji kwadrat`() {
        assertEquals("1:1", ImageTools.requested("!IMAGE: logo, minimal")?.aspect)
    }

    @Test
    fun `proporcje sprowadzone do trzech`() {
        assertEquals("16:9", ImageTools.requested("!IMAGE: x --ar 21:9")?.aspect)
        assertEquals("9:16", ImageTools.requested("!IMAGE: x --ar 2:3")?.aspect)
        assertEquals("1:1", ImageTools.requested("!IMAGE: x --ar 5:4")?.aspect)
        assertEquals("16:9", ImageTools.requested("!IMAGE: x --ar 3:2")?.aspect)
        assertEquals("16:9", ImageTools.requested("!IMAGE: x --ar 4:3")?.aspect)
        assertEquals("9:16", ImageTools.requested("!IMAGE: x --ar 3:4")?.aspect)
        assertEquals("1:1", ImageTools.requested("!IMAGE: x --ar 0:4")?.aspect)
    }

    @Test
    fun `prosba w liscie, cytacie albo odwrotnych apostrofach`() {
        assertEquals("a cat", ImageTools.requested("- !IMAGE: a cat")?.prompt)
        assertEquals("a cat", ImageTools.requested("> !IMAGE: `a cat`")?.prompt)
        assertEquals("a cat", ImageTools.requested("   !IMAGE: \"a cat\"")?.prompt)
    }

    @Test
    fun `wzmianka w srodku zdania to nie prosba`() {
        assertNull(ImageTools.requested("Mogę użyć !IMAGE: jeśli chcesz."))
        assertNull(ImageTools.requested("Zwykła odpowiedź bez obrazu."))
        assertNull(ImageTools.requested("!IMAGE:   \n"))
        assertNull(ImageTools.requested("!IMAGE: --ar 16:9"))
    }

    @Test
    fun `opis przyciety do 1500 znakow`() {
        assertEquals(1500, ImageTools.requested("!IMAGE: " + "a".repeat(4000))?.prompt?.length)
    }

    @Test
    fun `uzytkownik nie widzi linii z prosba ani pustego bloku kodu`() {
        val reply = "Oto obraz lisa:\n\n```\n!IMAGE: a fox --ar 1:1\n```\n\n\n\nMiłego dnia!"
        assertEquals("Oto obraz lisa:\n\nMiłego dnia!", ImageTools.withoutCall(reply))
        assertEquals("", ImageTools.withoutCall("!IMAGE: a fox"))
    }

    @Test
    fun `instrukcje mowia ile zostalo, a przy zerze zabraniaja`() {
        val some = ImageTools.instructions(left = 1, limit = 2)
        assertTrue(some.contains("!IMAGE:"))
        assertTrue(some.contains("jeszcze 1 z 2"))
        assertTrue(some.contains("niepełnoletni"))
        val none = ImageTools.instructions(left = 0, limit = 2)
        assertTrue(none.contains("Nie używaj !IMAGE"))
        assertFalse(none.contains("!IMAGE: <"))
    }
}
