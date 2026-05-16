package com.chad.player

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var canGoBackInternal = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        
        // Start Python FastAPI server
        val py = Python.getInstance()
        py.getModule("server").callAttr("start_server")

        webView = WebView(this)
        setContentView(webView)
        
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Critical for file:// -> http://127.0.0.1 and for audio
            allowUniversalAccessFromFileURLs = true
            allowFileAccessFromFileURLs = true
            setAllowFileAccessFromFileURLs(true)
            setAllowUniversalAccessFromFileURLs(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
        // YouTube iframe may request audio
            request.grant(request.resources)
            }
        }
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowUniversalAccessFromFileURLs = true
        webView.webViewClient = WebViewClient()

        webView.addJavascriptInterface(WebAppInterface(), "Android")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canGoBackInternal) {
                    webView.evaluateJavascript("navigateBack()", null)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        webView.loadUrl("file:///android_asset/www/index.html")
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun setCanGoBack(canGoBack: Boolean) {
            canGoBackInternal = canGoBack
        }
    }
}
