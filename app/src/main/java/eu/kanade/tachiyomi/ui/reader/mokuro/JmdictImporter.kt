package eu.kanade.tachiyomi.ui.reader.mokuro

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Pure JVM-testable core. Takes filesDir directly so tests don't need Android context.
 * isImported() checks File(filesDir, "jmdict.db").exists() — same path JmdictHelper reads.
 */
class JmdictImporterCore(private val filesDir: File) {

    private val dbFile get() = File(filesDir, "jmdict.db")

    fun isImported(): Boolean = dbFile.exists()

    @Throws(IOException::class)
    fun import(input: InputStream) {
        val tmp = File(filesDir, "jmdict.db.tmp")
        try {
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
            if (!tmp.renameTo(dbFile)) {
                tmp.delete()
                throw IOException("Failed to rename ${tmp.path} to ${dbFile.path}")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}

/**
 * Android wrapper: resolves a content Uri to an InputStream and delegates to JmdictImporterCore.
 */
class JmdictImporter(private val context: Context) {

    private val core = JmdictImporterCore(context.filesDir)

    fun isImported(): Boolean = core.isImported()

    suspend fun import(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open input stream for $uri")
        core.import(input)
    }
}
