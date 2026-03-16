package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.mokuro.MokuroPreferences
import kotlinx.collections.immutable.persistentListOf
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
        )
    }
}
