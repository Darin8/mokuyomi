package eu.kanade.tachiyomi.ui.reader.mokuro

import io.kotest.matchers.shouldBe
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class JmdictImporterTest {

    @TempDir
    lateinit var filesDir: File

    private fun core() = JmdictImporterCore(filesDir)

    @Test
    fun `copies content to jmdict db`() {
        val content = "fake db content".toByteArray()
        core().import(content.inputStream())
        File(filesDir, "jmdict.db").readBytes() shouldBe content
    }

    @Test
    fun `isImported returns false before import`() {
        core().isImported() shouldBe false
    }

    @Test
    fun `isImported returns true after import`() {
        core().import("data".toByteArray().inputStream())
        core().isImported() shouldBe true
    }

    @Test
    fun `failed import leaves no temp file and does not overwrite existing db`() {
        val original = "original".toByteArray()
        core().import(original.inputStream())

        val badStream = object : java.io.InputStream() {
            override fun read(): Int = throw IOException("read error")
        }
        try { core().import(badStream) } catch (_: IOException) {}

        File(filesDir, "jmdict.db").readBytes() shouldBe original
        File(filesDir, "jmdict.db.tmp").shouldNotExist()
    }

    @Test
    fun `import replaces existing db`() {
        core().import("old".toByteArray().inputStream())
        core().import("new".toByteArray().inputStream())
        File(filesDir, "jmdict.db").readText() shouldBe "new"
    }
}
