# JMdict Import Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users import a `jmdict.db` SQLite file from device storage via the Mokuro settings screen, placing it where `JmdictHelper` already expects it (`context.filesDir/jmdict.db`).

**Architecture:** `JmdictImporterCore(filesDir: File)` is a pure JVM class (no Android deps) that handles the atomic write: stream to `.tmp`, rename to `jmdict.db`, or clean up on failure. `JmdictImporter(context: Context)` wraps it to resolve a `Uri` via `ContentResolver` and run on `Dispatchers.IO`. `SettingsMokuroScreen` adds a file-picker launcher and a `TextPreference` button.

**Tech Stack:** Kotlin, Jetpack Compose, `ActivityResultContracts.GetContent`, `ContentResolver`, Coroutines (IO dispatcher), JUnit 5 + Kotest

**Interaction with JmdictHelper:** `JmdictHelper.ensureDatabase()` only copies from assets if `jmdict.db` is absent (`if (dbPath.exists()) return`). The import always writes before the reader opens, so a user-imported file is never overwritten. The `db` field in `JmdictHelper` is `lazy` — if the reader is already open when an import occurs, the old db connection stays active until the activity is recreated. This is acceptable for a settings-level import action.

---

## Chunk 1: JmdictImporter + Tests

### Task 1: Create JmdictImporter with unit tests

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/JmdictImporter.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/mokuro/JmdictImporterTest.kt`

`JmdictImporterCore` takes `filesDir: File` so tests can pass a `@TempDir` without any Android context.

`isImported()` is defined as `File(filesDir, "jmdict.db").exists()` — the same path `JmdictHelper` uses.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/eu/kanade/tachiyomi/ui/reader/mokuro/JmdictImporterTest.kt
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
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
JAVA_HOME=/tmp/jdk-17.0.10+7 bash gradlew :app:test --tests "*.JmdictImporterTest" 2>&1 | tail -20
```

Expected: compilation error (`JmdictImporterCore` not found)

- [ ] **Step 3: Create JmdictImporter.kt**

```kotlin
// app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/JmdictImporter.kt
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
```

- [ ] **Step 4: Run tests and confirm they pass**

```bash
JAVA_HOME=/tmp/jdk-17.0.10+7 bash gradlew :app:test --tests "*.JmdictImporterTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 5 tests passed

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/mokuro/JmdictImporter.kt
git add app/src/test/java/eu/kanade/tachiyomi/ui/reader/mokuro/JmdictImporterTest.kt
git commit -m "feat: add JmdictImporter with IO-isolated core and unit tests"
```

---

## Chunk 2: Settings UI

### Task 2: Add strings and import button to SettingsMokuroScreen

**Files:**
- Modify: `i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml`
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMokuroScreen.kt`

**String keys** — XML `name` attribute must match the `AYMR.strings.*` accessor exactly (moko codegen uses the same snake_case name):

| XML `name` | Kotlin accessor |
|---|---|
| `pref_mokuro_dictionary_group` | `AYMR.strings.pref_mokuro_dictionary_group` |
| `pref_mokuro_import_dictionary` | `AYMR.strings.pref_mokuro_import_dictionary` |
| `pref_mokuro_import_dictionary_summary_none` | `AYMR.strings.pref_mokuro_import_dictionary_summary_none` |
| `pref_mokuro_import_dictionary_summary_ready` | `AYMR.strings.pref_mokuro_import_dictionary_summary_ready` |
| `pref_mokuro_import_dictionary_success` | `AYMR.strings.pref_mokuro_import_dictionary_success` |
| `pref_mokuro_import_dictionary_error` | `AYMR.strings.pref_mokuro_import_dictionary_error` |

Only the base file needs updating — do NOT edit any locale-specific `strings.xml`.

- [ ] **Step 1: Add strings to `i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml`**

Add inside `<resources>`:

```xml
<string name="pref_mokuro_dictionary_group">Dictionary</string>
<string name="pref_mokuro_import_dictionary">Import JMdict database</string>
<string name="pref_mokuro_import_dictionary_summary_none">No dictionary imported — tap to import a jmdict.db file</string>
<string name="pref_mokuro_import_dictionary_summary_ready">Dictionary imported — tap to replace</string>
<string name="pref_mokuro_import_dictionary_success">Dictionary imported successfully</string>
<string name="pref_mokuro_import_dictionary_error">Failed to import dictionary</string>
```

- [ ] **Step 2: Update SettingsMokuroScreen.kt**

Notes before editing:
- `getTitleRes()` keeps its existing `@ReadOnlyComposable` annotation — do NOT remove it.
- `getPreferences()` does NOT have `@ReadOnlyComposable` in the existing file — do NOT add it. `rememberLauncherForActivityResult` must be called in a plain `@Composable` context.
- Toast pattern: use `context.toast(AYMR.strings.pref_mokuro_import_dictionary_success)` — the project has a `Context.toast(resource: StringResource)` extension in `eu.kanade.tachiyomi.util.system.ToastExtensions.kt` that accepts a moko `StringResource` directly. This works inside a coroutine because it takes the resolved `StringResource`, not a composable call.

Replace the full file:

```kotlin
package eu.kanade.presentation.more.settings.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.mokuro.MokuroPreferences
import eu.kanade.tachiyomi.ui.reader.mokuro.JmdictImporter
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsMokuroScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = AYMR.strings.label_mokuro

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { Injekt.get<MokuroPreferences>() }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val importer = remember { JmdictImporter(context) }

        // rememberLauncherForActivityResult must be called unconditionally at top level.
        val importLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                try {
                    importer.import(uri)
                    context.toast(AYMR.strings.pref_mokuro_import_dictionary_success)
                } catch (e: Exception) {
                    context.toast(AYMR.strings.pref_mokuro_import_dictionary_error)
                }
            }
        }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(AYMR.strings.pref_mokuro_server_group),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.serverUrl(),
                        title = stringResource(AYMR.strings.pref_mokuro_server_url),
                        subtitle = stringResource(AYMR.strings.pref_mokuro_server_url_summary),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.token(),
                        title = stringResource(AYMR.strings.pref_mokuro_token),
                        subtitle = stringResource(AYMR.strings.pref_mokuro_token_summary),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(AYMR.strings.pref_mokuro_dictionary_group),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(AYMR.strings.pref_mokuro_import_dictionary),
                        subtitle = if (importer.isImported()) {
                            stringResource(AYMR.strings.pref_mokuro_import_dictionary_summary_ready)
                        } else {
                            stringResource(AYMR.strings.pref_mokuro_import_dictionary_summary_none)
                        },
                        onClick = { importLauncher.launch("*/*") },
                    ),
                ),
            ),
        )
    }
}
```

- [ ] **Step 3: Verify by inspection**

Check:
- `getTitleRes()` retains `@ReadOnlyComposable` ✓
- `getPreferences()` has no `@ReadOnlyComposable` ✓
- `rememberLauncherForActivityResult` is at the top level of `getPreferences()`, not inside a lambda ✓
- `context.toast(AYMR.strings.*)` — uses the `StringResource` overload from `ToastExtensions.kt` ✓
- All 6 `AYMR.strings.pref_mokuro_*` keys match the table above ✓
- `importer.isImported()` checks `File(context.filesDir, "jmdict.db").exists()` via `JmdictImporterCore` ✓
- File picker uses `"*/*"` MIME type (not `"application/octet-stream"` — `.db` files are often not associated with that MIME on device file managers) ✓

- [ ] **Step 4: Commit**

```bash
git add i18n-aniyomi/src/commonMain/moko-resources/base/strings.xml
git add app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMokuroScreen.kt
git commit -m "feat: add JMdict import button to Mokuro settings screen"
```
