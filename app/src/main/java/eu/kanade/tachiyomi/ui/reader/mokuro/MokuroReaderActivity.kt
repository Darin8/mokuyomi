package eu.kanade.tachiyomi.ui.reader.mokuro

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import eu.kanade.tachiyomi.data.mokuro.MokuroApiClient
import eu.kanade.tachiyomi.data.mokuro.MokuroPreferences
import eu.kanade.tachiyomi.databinding.ActivityMokuroReaderBinding
import java.io.File
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MokuroReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMokuroReaderBinding

    private val preferences: MokuroPreferences = Injekt.get()
    private val apiClient: MokuroApiClient = Injekt.get()

    private var currentPage: Int = 1
    private var pageCount: Int = 1
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
        pageCount = intent.getIntExtra(EXTRA_PAGE_COUNT, 1)
        currentPage = savedInstanceState?.getInt(STATE_PAGE) ?: 1

        val webView = binding.webView
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(
            MokuroJsInterface { word, context -> onWordTapped(word, context) },
            "MokuroInterface",
        )
        webView.webViewClient = object : WebViewClient() {
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, currentPage)
    }

    override fun onDestroy() {
        super.onDestroy()
        jmdictHelper.close()
    }

    private fun loadCurrentPage() {
        val filename = "page_%03d.html".format(currentPage)
        val localFile = File(applicationContext.filesDir, "mokuro/$jobId/$filename")
        if (localFile.exists()) {
            binding.webView.loadUrl("file://${localFile.absolutePath}")
        } else {
            val serverUrl = preferences.serverUrl().get()
            val token = preferences.token().get()
            binding.webView.loadUrl(apiClient.pageUrl(serverUrl, token, jobId, currentPage))
        }
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

    fun goToPage(page: Int) {
        if (page in 1..pageCount) {
            currentPage = page
            loadCurrentPage()
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_PAGE_COUNT = "page_count"
        const val STATE_PAGE = "current_page"

        fun newIntent(context: Context, jobId: String, pageCount: Int): Intent =
            Intent(context, MokuroReaderActivity::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_PAGE_COUNT, pageCount)
            }
    }
}

class MokuroJsInterface(private val onWordTap: (word: String, context: String) -> Unit) {
    @JavascriptInterface
    fun onWordTap(word: String, sentenceContext: String) {
        onWordTap.invoke(word, sentenceContext)
    }
}
