package com.eve.htmlapp

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.util.Locale

/** Packages the provided EVE HTML as an Android app. */
class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var pendingWebPermission: PermissionRequest? = null
    private val mediaPermissionRequestCode = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(6, 16, 28)
        window.navigationBarColor = Color.rgb(6, 16, 28)

        tts = TextToSpeech(this, this)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(Color.rgb(6, 16, 28))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return request.url.host != "appassets.androidplatform.net"
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val needed = request.resources.mapNotNull { resource ->
                        when (resource) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                            else -> null
                        }
                    }.distinct()
                    if (needed.isEmpty()) {
                        request.deny()
                        return@runOnUiThread
                    }

                    val missing = needed.filter {
                        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        // Keep the WebView request alive while Android shows its permission dialog.
                        pendingWebPermission?.deny()
                        pendingWebPermission = request
                        requestPermissions(missing.toTypedArray(), mediaPermissionRequestCode)
                    } else {
                        val allowed = request.resources.filter { resource ->
                            when (resource) {
                                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                                    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                else -> false
                            }
                        }
                        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingWebPermission === request) pendingWebPermission = null
                super.onPermissionRequestCanceled(request)
            }
        }
        web.addJavascriptInterface(EveBridge(), "Android")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/eve.html")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != mediaPermissionRequestCode) return

        val request = pendingWebPermission ?: return
        pendingWebPermission = null
        val allowed = request.resources.filter { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                else -> false
            }
        }
        if (allowed.isEmpty()) {
            request.deny()
        } else {
            request.grant(allowed.toTypedArray())
        }
    }

    private inner class EveBridge {
        @JavascriptInterface
        fun speak(text: String, pitch: Float, rate: Float) {
            runOnUiThread {
                if (ttsReady) {
                    tts?.stop()
                    tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
                    tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
                    tts?.speak(text.take(800), TextToSpeech.QUEUE_FLUSH, null, "eve-tts")
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ar"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onPause() {
        if (::web.isInitialized) {
            web.evaluateJavascript("window.stopMediaFromNative && window.stopMediaFromNative();", null)
            web.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.removeJavascriptInterface("Android")
            web.destroy()
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
