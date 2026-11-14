package com.elison.codeshell

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Abre o code-server (VS Code completo) rodando localmente no Termux/proot.
 * Só aceita http para 127.0.0.1/localhost; qualquer outro endereço precisa ser https.
 */
class MainActivity : AppCompatActivity() {

    private data class PendingDownload(val url: String, val userAgent: String?, val cookie: String?)

    private lateinit var webView: WebView
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var baseUri: Uri

    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null
    private var mainFrameFailed = false
    private var retriesLeft = 0
    private var lastBackPress = 0L

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    // Com o editor aberto, exige duas pressões em 2 s para sair (evita fechar sem querer).
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackPress < BACK_EXIT_WINDOW_MS) {
                finish()
            } else {
                lastBackPress = now
                Toast.makeText(this@MainActivity, R.string.back_again, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Reagenda a conexão enquanto o servidor sobe (só roda depois do botão "Iniciar servidor").
    private val retryRunnable = Runnable {
        if (retriesLeft > 0) {
            retriesLeft--
            load()
        }
    }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val pending = pendingDownload
            pendingDownload = null
            if (uri != null && pending != null) startDownload(pending, uri)
        }

    private val termuxPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) sendRunCommand() else toast(R.string.termux_permission_denied)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        errorPanel = findViewById(R.id.errorPanel)
        errorText = findViewById(R.id.errorText)

        // targetSdk 35 força edge-to-edge no Android 15+: aplicamos os insets manualmente.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, backCallback)

        baseUri = normalize(prefs.getString(KEY_URL, DEFAULT_URL)) ?: Uri.parse(DEFAULT_URL)

        findViewById<Button>(R.id.startServerButton).setOnClickListener { startServer() }
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            cancelAutoRetry()
            load()
        }
        findViewById<Button>(R.id.changeUrlButton).setOnClickListener {
            cancelAutoRetry()
            showUrlDialog()
        }

        configureWebView()
        load()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
            builtInZoomControls = false
            textZoom = 100 // ignora a escala de fonte do sistema; o zoom é feito no próprio VS Code
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val uri = Uri.parse(url)
            if (!isInternal(uri)) {
                toast(R.string.download_unsupported)
                return@setDownloadListener
            }
            pendingDownload = PendingDownload(url, userAgent, CookieManager.getInstance().getCookie(url))
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            try {
                createDocument.launch(fileName)
            } catch (e: ActivityNotFoundException) {
                pendingDownload = null
                toast(R.string.download_failed)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return true
                if (isInternal(uri)) return false
                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                mainFrameFailed = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!mainFrameFailed) {
                    cancelAutoRetry()
                    setErrorVisible(false)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    mainFrameFailed = true
                    errorText.text = getString(
                        R.string.error_load,
                        baseUri.toString(),
                        error?.description ?: ""
                    )
                    setErrorVisible(true)
                    if (retriesLeft > 0) handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
                }
            }

            // O processo de renderização pode ser encerrado pelo sistema (pouca memória).
            // Retornar true evita o crash do app; recreate() monta um WebView novo.
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                toast(R.string.renderer_restarted)
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val intent = fileChooserParams?.createIntent() ?: return false
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    fileChooser.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }
        }
    }

    private fun load() {
        mainFrameFailed = false
        setErrorVisible(false)
        webView.loadUrl(baseUri.toString())
    }

    private fun setErrorVisible(visible: Boolean) {
        errorPanel.visibility = if (visible) View.VISIBLE else View.GONE
        backCallback.isEnabled = !visible
    }

    private fun cancelAutoRetry() {
        retriesLeft = 0
        handler.removeCallbacks(retryRunnable)
    }

    // ---- Início do servidor via Termux (RUN_COMMAND) -------------------------------------

    private fun startServer() {
        val granted = ContextCompat.checkSelfPermission(this, PERM_RUN_COMMAND) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) sendRunCommand() else termuxPermission.launch(PERM_RUN_COMMAND)
    }

    private fun sendRunCommand() {
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            action = TERMUX_RUN_COMMAND_ACTION
            putExtra("com.termux.RUN_COMMAND_PATH", START_SCRIPT_PATH)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        val started = try {
            startService(intent) != null
        } catch (e: RuntimeException) { // SecurityException, IllegalStateException etc.
            false
        }
        if (started) {
            toast(R.string.server_starting)
            handler.removeCallbacks(retryRunnable)
            retriesLeft = MAX_RETRIES
            handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
        } else {
            toast(R.string.termux_unavailable)
        }
    }

    // ---- Downloads ----------------------------------------------------------------------

    private fun startDownload(pending: PendingDownload, target: Uri) {
        toast(R.string.download_started)
        ioExecutor.execute {
            val ok = try {
                val conn = URL(pending.url).openConnection() as HttpURLConnection
                try {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    pending.cookie?.let { conn.setRequestProperty("Cookie", it) }
                    pending.userAgent?.let { conn.setRequestProperty("User-Agent", it) }
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        false
                    } else {
                        val out = contentResolver.openOutputStream(target)
                        if (out == null) {
                            false
                        } else {
                            conn.inputStream.use { input -> out.use { output -> input.copyTo(output) } }
                            true
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                false
            }
            if (!ok) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, target)
                } catch (e: Exception) {
                    // melhor esforço: o arquivo parcial pode permanecer
                }
            }
            runOnUiThread { toast(if (ok) R.string.download_done else R.string.download_failed) }
        }
    }

    // ---- URL ----------------------------------------------------------------------------

    private fun isInternal(uri: Uri): Boolean =
        uri.scheme.equals(baseUri.scheme, ignoreCase = true) &&
            uri.host.equals(baseUri.host, ignoreCase = true) &&
            effectivePort(uri) == effectivePort(baseUri)

    private fun effectivePort(uri: Uri): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun openExternal(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            // sem app para abrir o link: ignora
        }
    }

    /** http só é aceito para loopback; o resto precisa ser https. */
    private fun normalize(raw: String?): Uri? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val withScheme = if (text.contains("://")) text else "http://$text"
        val uri = Uri.parse(withScheme)
        val host = uri.host ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val isLoopback = host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)
        return when {
            scheme == "https" -> uri
            scheme == "http" && isLoopback -> uri
            else -> null
        }
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply {
            setText(baseUri.toString())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.change_url)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val parsed = normalize(input.text.toString())
                if (parsed == null) {
                    toast(R.string.invalid_url)
                } else {
                    baseUri = parsed
                    prefs.edit().putString(KEY_URL, parsed.toString()).apply()
                    load()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(applicationContext, resId, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ioExecutor.shutdown()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val PREFS = "codeshell"
        const val KEY_URL = "url"
        const val DEFAULT_URL = "http://127.0.0.1:8080"

        const val BACK_EXIT_WINDOW_MS = 2_000L
        const val RETRY_DELAY_MS = 5_000L
        const val MAX_RETRIES = 6

        const val PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        const val START_SCRIPT_PATH = "/data/data/com.termux/files/home/start-code-server.sh"
    }
}
