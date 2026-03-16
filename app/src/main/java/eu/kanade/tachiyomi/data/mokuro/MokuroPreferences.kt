// app/src/main/java/eu/kanade/tachiyomi/data/mokuro/MokuroPreferences.kt
package eu.kanade.tachiyomi.data.mokuro

import tachiyomi.core.common.preference.PreferenceStore

class MokuroPreferences(private val preferenceStore: PreferenceStore) {
    fun serverUrl() = preferenceStore.getString("mokuro_server_url", "")
    fun token() = preferenceStore.getString("mokuro_token", "")
}
