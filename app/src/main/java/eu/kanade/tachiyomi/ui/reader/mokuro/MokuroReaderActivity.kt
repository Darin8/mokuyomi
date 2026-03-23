package eu.kanade.tachiyomi.ui.reader.mokuro

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import eu.kanade.tachiyomi.data.mokuro.MokuroApiClient
import eu.kanade.tachiyomi.data.mokuro.MokuroPreferences
import eu.kanade.tachiyomi.databinding.ActivityMokuroReaderBinding
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MokuroReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMokuroReaderBinding

    private val preferences: MokuroPreferences = Injekt.get()
    private val apiClient: MokuroApiClient = Injekt.get()

    private var jobId: String = ""

    // Compose-observable state for the dictionary popup
    private val dictionaryVisible = mutableStateOf(false)
    private val dictionaryWord = mutableStateOf("")
    private val dictionaryContext = mutableStateOf("")

    private val jmdictHelper: JmdictHelper by lazy { JmdictHelper(this) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMokuroReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return finish()

        val webView = binding.webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                logcat(if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) LogPriority.ERROR else LogPriority.DEBUG) {
                    "WebView JS [${msg.sourceId()}:${msg.lineNumber()}] ${msg.message()}"
                }
                return true
            }
        }
        webView.addJavascriptInterface(
            MokuroJsInterface { word, context -> onWordTapped(word, context) },
            "MokuroInterface",
        )
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val serverUrl = preferences.serverUrl().get()
                val token = preferences.token().get()
                val url = request.url.toString()
                if (!url.startsWith(serverUrl) || url.contains("token=")) return null
                val separator = if (url.contains('?')) '&' else '?'
                val authedUrl = "$url${separator}token=$token"
                return try {
                    val conn = java.net.URL(authedUrl).openConnection() as java.net.HttpURLConnection
                    val mimeType = conn.contentType?.substringBefore(';')?.trim() ?: "application/octet-stream"
                    val encoding = conn.contentType?.substringAfter("charset=", "utf-8")?.trim() ?: "utf-8"
                    WebResourceResponse(mimeType, encoding, conn.inputStream)
                } catch (_: Exception) {
                    null
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectWordTapListeners(view)
            }
        }

        val overlayView = ComposeView(this).apply {
            setContent {
                val showDict by dictionaryVisible
                if (showDict) {
                    MaterialTheme {
                        DictionaryPopup(
                            word = dictionaryWord.value,
                            sentenceContext = dictionaryContext.value,
                            helper = jmdictHelper,
                            onDismiss = { dictionaryVisible.value = false },
                        )
                    }
                }
            }
        }
        (binding.root as FrameLayout).addView(overlayView)

        loadCurrentPage()
    }

    override fun onDestroy() {
        super.onDestroy()
        jmdictHelper.close()
    }

    private fun loadCurrentPage() {
        val serverUrl = preferences.serverUrl().get()
        val token = preferences.token().get()
        binding.webView.loadUrl(apiClient.viewerUrl(serverUrl, token, jobId))
    }

    private fun injectWordTapListeners(view: WebView) {
        val js = """
            (function() {
                document.querySelectorAll('span').forEach(function(span) {
                    span.addEventListener('click', function(e) {
                        e.stopPropagation();
                        var word = span.innerText.trim();
                        var parent = span.closest('div, p') || document.body;
                        var ctx = Array.from(parent.querySelectorAll('span'))
                            .map(function(s) { return s.innerText; })
                            .join('');
                        MokuroInterface.onWordTap(word, ctx);
                    });
                });
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun onWordTapped(word: String, sentenceContext: String) {
        runOnUiThread {
            dictionaryWord.value = word
            dictionaryContext.value = sentenceContext
            dictionaryVisible.value = true
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "job_id"

        fun newIntent(context: Context, jobId: String): Intent =
            Intent(context, MokuroReaderActivity::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
            }
    }
}

class MokuroJsInterface(private val onWordTap: (word: String, context: String) -> Unit) {
    @JavascriptInterface
    fun onWordTap(word: String, sentenceContext: String) {
        onWordTap.invoke(word, sentenceContext)
    }
}
