package eu.kanade.tachiyomi.ui.reader.mokuro

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryPopup(
    word: String,
    sentenceContext: String,
    helper: JmdictHelper,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<JmdictEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(word) {
        entries = withContext(Dispatchers.IO) { helper.lookup(word) }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(text = word, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            when {
                loading -> Text("Looking up\u2026")
                entries.isEmpty() -> Text("No results found for \"$word\"")
                else -> entries.forEach { entry ->
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(entry.reading, style = MaterialTheme.typography.titleMedium)
                    Text(entry.partsOfSpeech, style = MaterialTheme.typography.bodySmall)
                    entry.definitions.take(3).forEach { def ->
                        Text("\u2022 $def", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        addAnkiCard(context, entry.word, sentenceContext, entry.reading,
                            entry.definitions.take(3).joinToString("\n"))
                    }) {
                        Text("Add to Anki")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

fun addAnkiCard(
    context: Context,
    word: String,
    sentenceContext: String,
    reading: String,
    definitions: String,
) {
    try {
        val intent = Intent().apply {
            component = ComponentName("com.ichi2.anki", "com.ichi2.anki.IntentHandler")
            action = "com.ichi2.anki.CREATE_NOTE"
            putExtra("EXTRA_DECK_ID", 0L)
            putExtra("EXTRA_FRONT", "$word\n\n$sentenceContext")
            putExtra("EXTRA_BACK", "$reading\n\n$definitions")
        }
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "AnkiDroid is not installed", Toast.LENGTH_SHORT).show()
    }
}
