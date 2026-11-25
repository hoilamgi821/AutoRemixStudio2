package com.hieu.autoremix

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastBackPressTime = 0L

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            val clipData = data.clipData
            when {
                clipData != null -> {
                    val uris = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                    callback.onReceiveValue(uris)
                }
                data.data != null -> callback.onReceiveValue(arrayOf(data.data!!))
                else -> callback.onReceiveValue(null)
            }
        } else {
            callback.onReceiveValue(null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0A0A12")

        webView = WebView(this).apply {
            keepScreenOn = true
            setBackgroundColor(Color.parseColor("#0A0A12"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                mediaPlaybackRequiresUserGesture = false
            }
        }
        setContentView(webView)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val downloadHookJs = """
                    (function() {
                        if (window.__androidBlobHooked) return;
                        window.__androidBlobHooked = true;
                        function saveBlobLink(el) {
                            const name = el.getAttribute('download') || ('AutoRemix_' + Date.now() + '.webm');
                            fetch(el.href)
                                .then(r => r.blob())
                                .then(blob => {
                                    const reader = new FileReader();
                                    reader.onloadend = function() {
                                        const base64 = reader.result.split(',')[1];
                                        window.AndroidBridge.saveAudio(base64, name);
                                    };
                                    reader.readAsDataURL(blob);
                                });
                        }
                        const origClick = HTMLAnchorElement.prototype.click;
                        HTMLAnchorElement.prototype.click = function() {
                            if (this.href && this.href.startsWith('blob:') && this.hasAttribute('download')) {
                                saveBlobLink(this);
                                return;
                            }
                            return origClick.apply(this, arguments);
                        };
                        document.addEventListener('click', function(e) {
                            const a = e.target.closest('a[download]');
                            if (a && a.href && a.href.startsWith('blob:')) {
                                e.preventDefault();
                                saveBlobLink(a);
                            }
                        }, true);
                    })();
                """.trimIndent()
                webView.evaluateJavascript(downloadHookJs, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/ogg", "video/mp4"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    filePickerLauncher.launch(Intent.createChooser(intent, "Chọn file nhạc"))
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(this@MainActivity, "Bấm lần nữa để thoát AutoRemix", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun saveAudio(base64Data: String, fileName: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val safeName = fileName.ifBlank { "AutoRemix_${System.currentTimeMillis()}.webm" }
                val mimeType = when {
                    safeName.endsWith(".wav", true) -> "audio/wav"
                    safeName.endsWith(".mp3", true) -> "audio/mpeg"
                    safeName.endsWith(".ogg", true) -> "audio/ogg"
                    else -> "audio/webm"
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AutoRemix")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                } else {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "AutoRemix"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    FileOutputStream(File(dir, safeName)).use { it.write(bytes) }
                }

                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Đã lưu vào Downloads/AutoRemix/$safeName",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Lỗi lưu file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
