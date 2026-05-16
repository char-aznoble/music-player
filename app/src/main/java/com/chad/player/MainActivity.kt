package com.chad.player

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.webkit.WebViewAssetLoader
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var canGoBackInternal = false
    private lateinit var mediaSession: MediaSessionCompat

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setupMediaSession()
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        
        // Start Python server via Service
        val serviceIntent = Intent(this, PythonService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        webView = WebView(this)
        setContentView(webView)
        
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            private val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this@MainActivity))
                .build()

            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

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

        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "ChadPlayer")
        
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                webView.post { webView.evaluateJavascript("togglePlay()", null) }
            }
            override fun onPause() {
                webView.post { webView.evaluateJavascript("togglePlay()", null) }
            }
            override fun onSkipToNext() {
                webView.post { webView.evaluateJavascript("nextTrack()", null) }
            }
            override fun onSkipToPrevious() {
                webView.post { webView.evaluateJavascript("prevTrack()", null) }
            }
        })
        
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        mediaSession.setSessionActivity(pendingIntent)
        
        mediaSession.isActive = true
    }

    private fun updateNotification(title: String, artist: String, isPlaying: Boolean) {
        val channelId = "player"
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(R.drawable.ic_skip_previous, "Previous", null)
            .addAction(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play, "Play/Pause", null)
            .addAction(R.drawable.ic_skip_next, "Next", null)
            .build()

        notificationManager.notify(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun setCanGoBack(canGoBack: Boolean) {
            canGoBackInternal = canGoBack
        }

        @JavascriptInterface
        fun updateMetadata(title: String, artist: String, albumArtUrl: String) {
            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            
            thread {
                try {
                    val url = URL(albumArtUrl)
                    val bitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream())
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                mediaSession.setMetadata(metadataBuilder.build())
                
                runOnUiThread {
                    updateNotification(title, artist, true)
                }
            }
        }

        @JavascriptInterface
        fun updatePlaybackState(isPlaying: Boolean, positionMs: Long) {
            val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            val playbackState = PlaybackStateCompat.Builder()
                .setState(state, positionMs, 1.0f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
                .build()
            mediaSession.setPlaybackState(playbackState)
            
            // Sync notification when pause/play happens
            if (state == PlaybackStateCompat.STATE_PAUSED) {
                // If we have current track info, update the notification
                mediaSession.controller.metadata?.let { metadata ->
                    val title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: ""
                    val artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""
                    updateNotification(title, artist, false)
                }
            }
        }
    }
}

class PythonService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("player", "Player", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        Python.getInstance().getModule("server").callAttr("start_server")
        
        val notification = NotificationCompat.Builder(this, "player")
            .setContentTitle("Chad Player")
            .setContentText("Music server is running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
            
        startForeground(1, notification)
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?) = null
}
