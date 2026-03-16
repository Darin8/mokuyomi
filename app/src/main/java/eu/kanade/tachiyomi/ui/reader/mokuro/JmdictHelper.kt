package eu.kanade.tachiyomi.ui.reader.mokuro

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

data class JmdictEntry(
    val word: String,
    val reading: String,
    val partsOfSpeech: String,
    val definitions: List<String>,
)

@Serializable
data class DeinflectRule(val kanaIn: String, val kanaOut: String, val rulesIn: Int, val rulesOut: Int)

class JmdictHelper(private val context: Context) {

    private val dbPath: File get() = File(context.filesDir, "jmdict.db")

    private val db: SQLiteDatabase by lazy {
        ensureDatabase()
        SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private val deinflectRules: List<DeinflectRule> by lazy {
        val json = context.assets.open("deinflect.json").bufferedReader().readText()
        Json.decodeFromString<List<DeinflectRule>>(json)
    }

    private fun ensureDatabase() {
        if (dbPath.exists()) return
        context.assets.open("jmdict.db").use { input ->
            dbPath.outputStream().use { output -> input.copyTo(output) }
        }
    }

    fun lookup(word: String): List<JmdictEntry> {
        val exact = queryExact(word)
        if (exact.isNotEmpty()) return exact

        for (candidate in deinflectedForms(word)) {
            val results = queryExact(candidate)
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    private fun deinflectedForms(word: String): List<String> {
        return deinflectRules
            .filter { rule -> word.endsWith(rule.kanaIn) }
            .map { rule -> word.dropLast(rule.kanaIn.length) + rule.kanaOut }
            .distinct()
    }

    private fun queryExact(word: String): List<JmdictEntry> {
        val cursor = db.rawQuery(
            "SELECT word, reading, pos, definitions FROM entries WHERE word = ? LIMIT 3",
            arrayOf(word),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(JmdictEntry(
                        word = c.getString(0),
                        reading = c.getString(1),
                        partsOfSpeech = c.getString(2),
                        definitions = c.getString(3).split("\t"),
                    ))
                }
            }
        }
    }

    fun close() = db.close()
}
