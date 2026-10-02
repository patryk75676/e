package pl.cyphr.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zalaczniki: zapis w rozmowie, bezpieczne sciezki, tekst z pliku i ile obrazow idzie do modelu. */
class AttachmentTest {
    private val photo = Attachment(
        kind = Attachment.Kind.Image, name = "zrzut.png", mime = "image/png", size = 123_456,
        files = listOf("att/7/a.png"), width = 1080, height = 2400,
    )

    @Test
    fun `zapis i odczyt daja to samo`() {
        val pdf = Attachment(Attachment.Kind.Pdf, "umowa.pdf", "application/pdf", 99, listOf("att/7/p1.jpg", "att/7/p2.jpg"), 1400, 1980, pages = 12)
        val txt = Attachment(Attachment.Kind.Text, "main.kt", "text/plain", 5, text = "fun main() {}\n", truncated = true)
        val gen = Attachment(Attachment.Kind.Generated, "Obraz CYPHR", "image/png", 10, listOf("att/7/g.png"), 1024, 1024, prompt = "a fox")
        for (a in listOf(photo, pdf, txt, gen)) {
            assertEquals(a, Attachment.fromJson(JSONObject(a.toJson().toString())))
        }
    }

    @Test
    fun `nieznany rodzaj z nowszej wersji jest pomijany`() {
        assertNull(Attachment.fromJson(JSONObject().put("kind", "audio").put("name", "x")))
    }

    @Test
    fun `sciezki spoza katalogu aplikacji odpadaja`() {
        val o = photo.toJson().put("files", JSONArray(listOf("att/7/ok.png", "../shared_prefs/x.xml", "/data/x", "att//x", "att/../../x", "")))
        assertEquals(listOf("att/7/ok.png"), Attachment.fromJson(o)?.files)
        assertTrue(Attachment.safePath("att-staged/x.jpg"))
        assertFalse(Attachment.safePath("att/7/../../../x"))
    }

    @Test
    fun `obraz zmniejszany do dluzszego boku z zachowaniem proporcji`() {
        assertEquals(1568 to 706, Attachments.fit(4000, 1800, 1568))
        assertEquals(706 to 1568, Attachments.fit(1800, 4000, 1568))
        assertEquals(800 to 600, Attachments.fit(800, 600, 1568))
        assertEquals(1568 to 1, Attachments.fit(100_000, 10, 1568))
        assertEquals(0 to 0, Attachments.fit(0, 0, 1568))
    }

    @Test
    fun `tekst z pliku bez BOM, obciety i odrzucony, gdy binarny`() {
        assertEquals("zażółć" to false, Attachments.textOf("﻿zażółć".toByteArray(), moreInFile = false))
        assertEquals(true, Attachments.textOf("abc".toByteArray(), moreInFile = true).second)
        val (long, cut) = Attachments.textOf("x".repeat(Attachments.TEXT_MAX_CHARS + 10).toByteArray(), false)
        assertEquals(Attachments.TEXT_MAX_CHARS, long.length)
        assertTrue(cut)
        val e = assertThrows(AttachError::class.java) { Attachments.textOf(byteArrayOf(0x50, 0x4b, 0x00, 0x03), false) }
        assertEquals("To nie jest plik tekstowy.", e.message)
    }

    @Test
    fun `opisy i rozmiary po ludzku`() {
        assertEquals("Załączony obraz: zrzut.png", Attachments.describe(photo))
        assertEquals("Stworzony obraz: a fox", Attachments.describe(Attachment(Attachment.Kind.Generated, "x", prompt = "a fox")))
        assertEquals("850 B", Attachments.humanSize(850))
        assertEquals("12 KB", Attachments.humanSize(12 * 1024))
        assertEquals("3,4 MB", Attachments.humanSize((3.4 * 1024 * 1024).toLong()))
    }

    @Test
    fun `do modelu ida obrazy z ostatnich trzech wiadomosci, najwyzej osiem`() {
        fun user(n: Int) = ChatMessage("", true, List(n) { photo })
        val ai = ChatMessage("ok", false)
        val chat = listOf(user(1), ai, user(1), ai, user(1), ai, user(1), ai)
        assertEquals(setOf(2, 4, 6), Api.imageMessages(chat))
        // Czwarta z konca z 4 obrazami przekroczylaby osiem — dalej juz nie siegamy.
        assertEquals(setOf(2, 4), Api.imageMessages(listOf(user(4), ai, user(4), ai, user(4))))
        assertEquals(emptySet<Int>(), Api.imageMessages(listOf(ChatMessage("bez obrazow", true), ai)))
    }

    @Test
    fun `obraz liczy sie do tokenow, a plik tekstowy swoja trescia`() {
        val plain = messageTokens(ChatMessage("hej", true))
        assertEquals(plain + IMAGE_TOKENS, messageTokens(ChatMessage("hej", true, listOf(photo))))
        val txt = Attachment(Attachment.Kind.Text, "a.txt", text = "slowo ".repeat(400))
        assertTrue(messageTokens(ChatMessage("hej", true, listOf(txt))) > plain + 300)
    }
}
