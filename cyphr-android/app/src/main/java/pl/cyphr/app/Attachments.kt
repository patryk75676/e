package pl.cyphr.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Zalacznik wiadomosci: zdjecie albo zrzut ekranu, PDF, plik tekstowy albo obraz stworzony
 * przez model. Pliki leza wylacznie w prywatnym katalogu aplikacji; [files] to sciezki
 * wzgledem filesDir (albo cacheDir, poki zalacznik czeka w polu wpisywania).
 */
data class Attachment(
    val kind: Kind,
    val name: String,
    val mime: String = "",
    /** Rozmiar oryginalu w bajtach — tylko do pokazania. */
    val size: Long = 0,
    /** Obrazy do pokazania i wyslania modelowi: zdjecie, strony PDF-a, obraz modelu. */
    val files: List<String> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    /** Liczba wszystkich stron PDF-a (w [files] sa tylko pierwsze). */
    val pages: Int = 0,
    /** Tresc pliku tekstowego — idzie do modelu jako tekst. */
    val text: String = "",
    val truncated: Boolean = false,
    /** Opis, z ktorego model stworzyl obraz. */
    val prompt: String = "",
) {
    enum class Kind(val key: String) {
        Image("image"), Pdf("pdf"), Text("text"), Generated("generated");

        companion object {
            fun of(key: String): Kind? = entries.firstOrNull { it.key == key }
        }
    }

    /** Czy model dostaje go jako obraz (zdjecie, strony PDF-a). Obraz modelu idzie jako opis. */
    val seenAsImage: Boolean get() = kind == Kind.Image || kind == Kind.Pdf

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.key)
        .put("name", name)
        .put("mime", mime)
        .put("size", size)
        .put("files", JSONArray(files))
        .put("width", width)
        .put("height", height)
        .put("pages", pages)
        .put("text", text)
        .put("truncated", truncated)
        .put("prompt", prompt)

    companion object {
        /** Nieznany rodzaj (np. z nowszej wersji aplikacji) jest pomijany, a nie psuje rozmowy. */
        fun fromJson(o: JSONObject): Attachment? {
            val kind = Kind.of(o.optString("kind")) ?: return null
            val files = o.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?.filter { it.isNotBlank() && safePath(it) }.orEmpty()
            return Attachment(
                kind = kind,
                name = o.optString("name"),
                mime = o.optString("mime"),
                size = o.optLong("size"),
                files = files,
                width = o.optInt("width"),
                height = o.optInt("height"),
                pages = o.optInt("pages"),
                text = o.optString("text"),
                truncated = o.optBoolean("truncated"),
                prompt = o.optString("prompt"),
            )
        }

        /** Tylko sciezki wewnatrz katalogow aplikacji — bez „..” i sciezek bezwzglednych. */
        internal fun safePath(p: String): Boolean =
            !p.startsWith("/") && p.split('/').none { it == ".." || it.isEmpty() }
    }
}

/** Wyjatek z komunikatem dla uzytkownika — pokazujemy go tak, jak jest. */
class AttachError(message: String) : Exception(message)

object Attachments {
    /** Tyle zalacznikow najwyzej w jednej wiadomosci. */
    const val MAX_PER_MESSAGE = 4
    /** Dluzszy bok obrazu dla modelu. Wiecej modele i tak zmniejszaja, a zapytanie rosnie. */
    const val IMAGE_MAX_SIDE = 1568
    /** Strony PDF-a, ktore model widzi jako obrazy. */
    const val PDF_PAGES = 4
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    /** Obrazy powyzej tylu pikseli odrzucamy — ochrona przed „bombami” dekompresji. */
    private const val MAX_PIXELS = 100_000_000L
    private const val TEXT_MAX_BYTES = 256 * 1024
    const val TEXT_MAX_CHARS = 100_000

    private const val STAGED = "att-staged"

    private val textMimes = setOf(
        "application/json", "application/xml", "application/javascript", "application/x-javascript",
        "application/x-sh", "application/x-yaml", "application/yaml", "application/toml",
        "application/sql", "application/x-python", "application/x-kotlin", "application/csv",
    )
    private val textExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "mjs", "ts", "tsx",
        "jsx", "kt", "kts", "java", "py", "sh", "bash", "zsh", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs",
        "rb", "php", "sql", "yaml", "yml", "toml", "ini", "cfg", "conf", "log", "properties", "gradle",
        "swift", "dart", "lua", "pl", "r", "scala", "vue", "svelte", "env", "gitignore", "dockerfile", "srt", "vtt",
    )

    /** Katalog zalacznikow konta — osobny, jak rozmowy. */
    private fun accountDir(context: Context, uid: Long) = File(File(context.filesDir, "att"), uid.toString())

    fun file(context: Context, path: String): File =
        if (path.startsWith("$STAGED/")) File(context.cacheDir, path) else File(context.filesDir, path)

    private fun stagedDir(context: Context) = File(context.cacheDir, STAGED).apply { mkdirs() }

    // ---------------- import ----------------

    /**
     * Wczytuje plik wskazany przez uzytkownika (galeria, pliki, schowek, „Udostepnij”) do
     * prywatnego katalogu: obraz zmniejszony, PDF jako obrazy stron, tekst jako tekst.
     * Wlasnych plikow aplikacji nie przyjmujemy — inaczej obca aplikacja mogla podsunac
     * „do wyslania” np. nasze ustawienia.
     */
    suspend fun import(context: Context, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw AttachError(tr("Tego pliku nie da się otworzyć.", "This file can't be opened."))
        if (uri.authority == context.packageName + ".files") throw AttachError(tr("Tego pliku nie da się otworzyć.", "This file can't be opened."))
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0).orEmpty()
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) {
        }
        if (size > MAX_FILE_BYTES) throw AttachError(tr("Plik jest za duży — najwyżej ${MAX_FILE_BYTES / 1024 / 1024} MB.", "The file is too big — at most ${MAX_FILE_BYTES / 1024 / 1024} MB."))
        val mime = (try { resolver.getType(uri) } catch (_: Exception) { null }).orEmpty().lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp") ->
                importImage(context, uri, name.ifBlank { tr("Obraz", "Image") }, size)
            mime == "application/pdf" || ext == "pdf" -> importPdf(context, uri, name.ifBlank { tr("Dokument.pdf", "Document.pdf") }, size)
            mime.startsWith("text/") || mime in textMimes || ext in textExtensions ->
                importText(context, uri, name.ifBlank { tr("Plik.txt", "File.txt") }, size, mime)
            else -> throw AttachError(
                tr(
                    "Tego typu pliku nie obsłużę. Wyślij zdjęcie, PDF albo plik tekstowy.",
                    "This type of file isn't supported. Send a photo, a PDF or a text file.",
                ),
            )
        }
    }

    private fun newStaged(context: Context, ext: String) = File(stagedDir(context), UUID.randomUUID().toString() + "." + ext)

    private fun rel(context: Context, f: File): String =
        if (f.path.startsWith(context.cacheDir.path + "/")) f.path.removePrefix(context.cacheDir.path + "/")
        else f.path.removePrefix(context.filesDir.path + "/")

    /** Rozmiar docelowy: dluzszy bok najwyzej [maxSide]. */
    internal fun fit(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val longer = max(width, height)
        if (longer <= maxSide) return width to height
        val k = maxSide.toDouble() / longer
        return max(1, (width * k).roundToInt()) to max(1, (height * k).roundToInt())
    }

    private fun importImage(context: Context, uri: Uri, name: String, size: Long): Attachment {
        val bitmap = decode(context, uri, IMAGE_MAX_SIDE)
        try {
            val out = writeImage(context, bitmap)
            return Attachment(
                kind = Attachment.Kind.Image,
                name = name,
                mime = out.second,
                size = if (size > 0) size else out.first.length(),
                files = listOf(rel(context, out.first)),
                width = bitmap.width,
                height = bitmap.height,
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Dekoduje od razu w zmniejszonym rozmiarze (probkowanie przy dekodowaniu, wiec 50-megapikselowe
     * zdjecie nie trafia w calosci do pamieci) i z wlasciwym obrotem oraz odbiciem z EXIF.
     */
    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) throw AttachError(tr("Nie udało się otworzyć obrazu.", "Couldn't open the image."))
            if (w.toLong() * h > MAX_PIXELS) throw AttachError(tr("Obraz jest zbyt duży.", "The image is too large."))
            var sample = 1
            while (max(w, h) / (sample * 2) >= maxSide) sample *= 2
            val raw = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw AttachError(tr("Nie udało się otworzyć obrazu.", "Couldn't open the image."))
            val orientation = try {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val (tw, th) = fit(raw.width, raw.height, maxSide)
            val matrix = Matrix().apply {
                postScale(tw.toFloat() / raw.width, th.toFloat() / raw.height)
                orient(this, orientation)
            }
            if (matrix.isIdentity) return raw
            val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            if (out !== raw) raw.recycle()
            return out
        } catch (e: AttachError) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw AttachError(tr("Obraz jest zbyt duży.", "The image is too large."))
        } catch (e: Exception) {
            throw AttachError(tr("Nie udało się otworzyć obrazu.", "Couldn't open the image."))
        }
    }

    /** Obrot i odbicie zapisane przez aparat w EXIF — tak, jak zdjecie widac w galerii. */
    internal fun orient(m: Matrix, orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(-90f)
        }
    }

    /** JPEG, a przezroczyste obrazy (np. logo) jako PNG — w JPEG-u tlo zrobiloby sie czarne. */
    private fun writeImage(context: Context, bitmap: Bitmap): Pair<File, String> {
        val png = bitmap.hasAlpha()
        val f = newStaged(context, if (png) "png" else "jpg")
        f.outputStream().use {
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 85, it)
        }
        return f to (if (png) "image/png" else "image/jpeg")
    }

    private fun importPdf(context: Context, uri: Uri, name: String, size: Long): Attachment {
        // PdfRenderer potrzebuje pliku, po ktorym da sie skakac — strumien z innej aplikacji nie wystarczy.
        val tmp = File(stagedDir(context), UUID.randomUUID().toString() + ".pdf")
        try {
            var copied = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_FILE_BYTES) throw AttachError(tr("Plik jest za duży — najwyżej ${MAX_FILE_BYTES / 1024 / 1024} MB.", "The file is too big — at most ${MAX_FILE_BYTES / 1024 / 1024} MB."))
                        output.write(buf, 0, n)
                    }
                }
            } ?: throw AttachError(tr("Nie udało się otworzyć pliku.", "Couldn't open the file."))
            val fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            // Od udanego otwarcia deskryptor nalezy do PdfRenderer; przy bledzie zamykamy go sami.
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: SecurityException) {
                fd.close()
                throw AttachError(tr("Ten PDF jest zabezpieczony hasłem.", "This PDF is password-protected."))
            } catch (e: Exception) {
                fd.close()
                throw AttachError(tr("Nie udało się otworzyć PDF-a.", "Couldn't open the PDF."))
            }
            renderer.use { r ->
                if (r.pageCount == 0) throw AttachError(tr("PDF jest pusty.", "The PDF is empty."))
                val files = ArrayList<String>()
                var firstW = 0
                var firstH = 0
                for (i in 0 until minOf(PDF_PAGES, r.pageCount)) {
                    r.openPage(i).use { page ->
                        // Szerokosc jak czytelny skan A4; strona pozioma zachowuje proporcje.
                        val (w, h) = fit(page.width * 3, page.height * 3, 1400)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val f = newStaged(context, "jpg")
                        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                        if (i == 0) { firstW = w; firstH = h }
                        bmp.recycle()
                        files += rel(context, f)
                    }
                }
                return Attachment(
                    kind = Attachment.Kind.Pdf,
                    name = name,
                    mime = "application/pdf",
                    size = if (size > 0) size else copied,
                    files = files,
                    width = firstW,
                    height = firstH,
                    pages = r.pageCount,
                )
            }
        } finally {
            tmp.delete()
        }
    }

    private fun importText(context: Context, uri: Uri, name: String, size: Long, mime: String): Attachment {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (buf.size() < TEXT_MAX_BYTES) {
                val n = input.read(chunk, 0, minOf(chunk.size, TEXT_MAX_BYTES - buf.size()))
                if (n < 0) break
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        } ?: throw AttachError(tr("Nie udało się otworzyć pliku.", "Couldn't open the file."))
        val (text, cut) = textOf(bytes, size > bytes.size)
        return Attachment(
            kind = Attachment.Kind.Text,
            name = name,
            mime = mime.ifBlank { "text/plain" },
            size = if (size > 0) size else bytes.size.toLong(),
            text = text,
            truncated = cut,
        )
    }

    /** Tekst z bajtow pliku. Bajt zerowy to znak pliku binarnego — takiego modelowi nie wysylamy. */
    internal fun textOf(bytes: ByteArray, moreInFile: Boolean): Pair<String, Boolean> {
        if (bytes.any { it == 0.toByte() }) throw AttachError(tr("To nie jest plik tekstowy.", "This isn't a text file."))
        var text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        var cut = moreInFile
        if (text.length > TEXT_MAX_CHARS) { text = text.take(TEXT_MAX_CHARS); cut = true }
        return text to cut
    }

    // ---------------- obrazy modelu ----------------

    /** Obraz stworzony przez model — od razu na miejscu, w katalogu konta. */
    fun saveGenerated(context: Context, uid: Long, bytes: ByteArray, mime: String, prompt: String): Attachment {
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            else -> "jpg"
        }
        val dir = accountDir(context, uid).apply { mkdirs() }
        val f = File(dir, UUID.randomUUID().toString() + "." + ext)
        f.writeBytes(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        return Attachment(
            kind = Attachment.Kind.Generated,
            name = tr("Obraz CYPHR", "CYPHR image"),
            mime = mime,
            size = bytes.size.toLong(),
            files = listOf(rel(context, f)),
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
            prompt = prompt,
        )
    }

    // ---------------- zycie plikow ----------------

    /**
     * Przenosi zalaczniki z pola wpisywania do katalogu konta — od tej chwili naleza
     * do wiadomosci i znikaja razem z nia.
     */
    fun commit(context: Context, uid: Long, staged: List<Attachment>): List<Attachment> {
        val dir = accountDir(context, uid).apply { mkdirs() }
        return staged.map { a ->
            a.copy(
                files = a.files.map { p ->
                    if (!p.startsWith("$STAGED/")) return@map p
                    val src = file(context, p)
                    val dst = File(dir, src.name)
                    if (!src.renameTo(dst)) {
                        src.copyTo(dst, overwrite = true)
                        src.delete()
                    }
                    rel(context, dst)
                },
            )
        }
    }

    /** Usuwa pliki zalacznikow (np. usunieta wiadomosc albo zalacznik wyjety z pola). */
    fun delete(context: Context, attachments: List<Attachment>) {
        attachments.flatMap { it.files }.forEach { p -> try { file(context, p).delete() } catch (_: Exception) {} }
    }

    /**
     * Sprzata pliki konta, do ktorych nie prowadzi zadna wiadomosc — np. po przycieciu
     * bardzo dlugiej rozmowy albo przerwanym zapisie. Swieze pliki zostaja: wiadomosc,
     * do ktorej naleza, moze jeszcze isc na dysk (odpowiedz w tle, obraz w drodze).
     */
    fun collect(context: Context, uid: Long, chats: List<Chat>, now: Long = System.currentTimeMillis()) {
        val used = chats.flatMap { c -> c.messages.flatMap { m -> m.attachments.flatMap { it.files } } }.toSet()
        accountDir(context, uid).listFiles()?.forEach { f ->
            if (rel(context, f) !in used && now - f.lastModified() > COLLECT_AFTER_MS) f.delete()
        }
    }

    /** Tyle co najmniej musi miec plik bez wiadomosci, zeby sprzatanie go usunelo. */
    internal const val COLLECT_AFTER_MS = 30 * 60_000L

    /** Porzucone zalaczniki z pola wpisywania (np. z poprzedniego uruchomienia). */
    fun clearStaged(context: Context, olderThanMs: Long = 24 * 3600_000L) {
        val now = System.currentTimeMillis()
        stagedDir(context).listFiles()?.forEach { if (now - it.lastModified() > olderThanMs) it.delete() }
    }

    // ---------------- udostepnianie i zapis ----------------

    /** Udostepnienie obrazu innej aplikacji — tylko ten plik i tylko do odczytu. */
    fun shareIntent(context: Context, path: String, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file(context, path))
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime.ifBlank { "image/*" })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, tr("Udostępnij obraz", "Share image")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Czy da sie zapisac do galerii bez zadnych uprawnien (Android 10+). */
    val canSaveToGallery: Boolean get() = Build.VERSION.SDK_INT >= 29

    /** Zapis do galerii (Obrazy/CYPHR). Android 10+ nie wymaga do tego zadnej zgody. */
    suspend fun saveToGallery(context: Context, path: String, mime: String): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext false
        val src = file(context, path)
        if (!src.exists()) return@withContext false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "CYPHR_" + System.currentTimeMillis() + "." + src.extension)
            put(MediaStore.Images.Media.MIME_TYPE, mime.ifBlank { "image/jpeg" })
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CYPHR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    // ---------------- dla modelu ----------------

    /** Obraz jako data URL dla modelu z obsluga obrazow. */
    fun dataUrl(context: Context, path: String, mime: String): String? = try {
        val bytes = file(context, path).readBytes()
        "data:" + mime.ifBlank { "image/jpeg" } + ";base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    /** Krotki opis zalacznika — tam, gdzie nie ma miejsca na sam obraz (historia, notatka). */
    fun describe(a: Attachment): String = when (a.kind) {
        Attachment.Kind.Image -> tr("Załączony obraz: ${a.name}", "Attached image: ${a.name}")
        Attachment.Kind.Pdf -> tr("Załączony PDF: ${a.name}, stron: ${a.pages}", "Attached PDF: ${a.name}, pages: ${a.pages}")
        Attachment.Kind.Text -> tr("Załączony plik: ${a.name}", "Attached file: ${a.name}")
        Attachment.Kind.Generated -> tr("Stworzony obraz: ${a.prompt}", "Created image: ${a.prompt}")
    }

    /** Rozmiar po ludzku: 850 B, 12 KB, 3,4 MB (po angielsku 3.4 MB). */
    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> String.format(appLocale, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
