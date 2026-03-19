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
