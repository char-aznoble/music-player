package com.chad.player

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.ViewGroup
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
import androidx.media.session.MediaButtonReceiver
import androidx.webkit.WebViewAssetLoader
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private var webView: WebView? = null
    private var canGoBackInternal = false
    private var pythonService: PythonService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PythonService.LocalBinder
            pythonService = binder.getService()
            isBound = true
            
            webView = pythonService?.getPersistentWebView()
            
            webView?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                setContentView(wv)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            pythonService = null
        }
    }

    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        
        val serviceIntent = Intent(this, PythonService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, connection, BIND_AUTO_CREATE)

        // Request Ignore Battery Optimizations
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pythonService?.canGoBack == true) {
                    webView?.evaluateJavascript("navigateBack()", null)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        webView?.onResume()
    }

    override fun onStop() {
        super.onStop()
        webView?.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            (webView?.parent as? ViewGroup)?.removeView(webView)
            unbindService(connection)
            isBound = false
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun setCanGoBack(canGoBack: Boolean) {
            canGoBackInternal = canGoBack
        }
    }
}

class PythonService : Service() {
    private lateinit var mediaSession: MediaSessionCompat
    private var webView: WebView? = null
    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    var canGoBack: Boolean = false
    private var lastIsPlaying: Boolean? = null
    private var lastTitle: String? = null

    inner class LocalBinder : android.os.Binder() {
        fun getService(): PythonService = this@PythonService
    }

    override fun onCreate() {
        super.onCreate()
        
        // Setup WakeLock here in Service
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChadPlayer::AudioWakeLock")
        wakeLock?.acquire()

        setupMediaSession()
        initWebView()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("player", "Player", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webView = WebView(applicationContext)
        webView?.let { wv ->
            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            }
            
            wv.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                }
            }

            wv.webViewClient = object : WebViewClient() {
                private val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this@PythonService))
                    .build()

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): android.webkit.WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }
            }

            wv.addJavascriptInterface(ServiceWebAppInterface(), "Android")
            wv.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
        }
    }

    fun getPersistentWebView(): WebView? = webView

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "ChadPlayer")
        
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                webView?.post { webView?.evaluateJavascript("togglePlay()", null) }
            }
            override fun onPause() {
                webView?.post { webView?.evaluateJavascript("togglePlay()", null) }
            }
            override fun onSkipToNext() {
                webView?.post { webView?.evaluateJavascript("nextTrack()", null) }
            }
            override fun onSkipToPrevious() {
                webView?.post { webView?.evaluateJavascript("prevTrack()", null) }
            }
            override fun onSeekTo(pos: Long) {
                webView?.post { webView?.evaluateJavascript("player.seekTo(${pos / 1000}, true)", null) }
            }
        })

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        mediaSession.setSessionActivity(pendingIntent)
        mediaSession.setMediaButtonReceiver(null)
        mediaSession.isActive = true
    }

    fun handleUpdateMetadata(title: String, artist: String, albumArtUrl: String) {
        if (title == lastTitle) return
        lastTitle = title

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)

        mediaSession.setMetadata(metadataBuilder.build())
        updateNotification(title, artist, true)

        thread {
            try {
                val url = URL(albumArtUrl)
                val bitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream())
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                mediaSession.setMetadata(metadataBuilder.build())
                updateNotification(title, artist, true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun handleUpdatePlaybackState(isPlaying: Boolean, positionMs: Long) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, positionMs, 1.0f, SystemClock.elapsedRealtime())
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
            .build()
        mediaSession.setPlaybackState(playbackState)

        if (isPlaying != lastIsPlaying) {
            lastIsPlaying = isPlaying
            mediaSession.controller.metadata?.let { metadata ->
                val title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: ""
                val artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""
                updateNotification(title, artist, isPlaying)
            }
        }
    }

    private fun updateNotification(title: String, artist: String, isPlaying: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val prevIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        val playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)
        val nextIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)

        val notification = NotificationCompat.Builder(this, "player")
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(R.drawable.ic_skip_previous, "Previous", prevIntent)
            .addAction(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play, "Play/Pause", playPauseIntent)
            .addAction(R.drawable.ic_skip_next, "Next", nextIntent)
            .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        
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

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
        wakeLock?.release()
    }

    inner class ServiceWebAppInterface {
        @JavascriptInterface
        fun setCanGoBack(canGoBack: Boolean) {
            this@PythonService.canGoBack = canGoBack
        }

        @JavascriptInterface
        fun updateMetadata(title: String, artist: String, albumArtUrl: String) {
            handleUpdateMetadata(title, artist, albumArtUrl)
        }

        @JavascriptInterface
        fun updatePlaybackState(isPlaying: Boolean, positionMs: Long) {
            handleUpdatePlaybackState(isPlaying, positionMs)
        }
    }
}
