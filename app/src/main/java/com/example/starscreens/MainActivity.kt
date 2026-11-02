package com.example.starscreens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// ═══════════════════════════════════════════════════════════════
// 1) MainActivity
// ═══════════════════════════════════════════════════════════════
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString StarScreensApp/1.0"
            setSupportZoom(false)
            builtInZoomControls = false
        }

        WebView.setWebContentsDebuggingEnabled(true)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        // ⭐ الجسر مع JavaScript
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidPlayer")

        // ⚠️⚠️ هنا تضع رابط GitHub Pages الخاص بك ⚠️⚠️
        webView.loadUrl("https://rkabyrama26-alt.github.io/اسم-المستودع-هنا/")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onResume() { super.onResume(); webView.onResume(); webView.resumeTimers() }
    override fun onPause() { webView.onPause(); webView.pauseTimers(); super.onPause() }

    override fun onDestroy() {
        webView.apply {
            loadUrl("about:blank"); stopLoading(); clearHistory()
            removeAllViews(); destroy()
        }
        super.onDestroy()
    }
}

// ═══════════════════════════════════════════════════════════════
// 2) WebAppInterface
// ═══════════════════════════════════════════════════════════════
class WebAppInterface(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun playStream(url: String, name: String, mode: String) {
        if (url.isBlank()) { toast("رابط غير صالح"); return }
        mainHandler.post {
            try {
                val intent = Intent(context, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_URL, url)
                    putExtra(PlayerActivity.EXTRA_NAME, name)
                    putExtra(PlayerActivity.EXTRA_MODE, mode)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                toast("تعذر فتح المشغل: ${e.message}")
            }
        }
    }

    @JavascriptInterface fun isAvailable(): Boolean = true
    @JavascriptInterface fun getAppVersion(): String = "1.0.0"

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }
}

// ═══════════════════════════════════════════════════════════════
// 3) PlayerActivity (ExoPlayer)
// ═══════════════════════════════════════════════════════════════
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "stream_url"
        const val EXTRA_NAME = "stream_name"
        const val EXTRA_MODE = "stream_mode"
    }

    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView

    private var player: ExoPlayer? = null
    private var streamUrl = ""
    private var streamName = ""
    private var streamMode = "strong"

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        streamUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        streamName = intent.getStringExtra(EXTRA_NAME) ?: "بث مباشر"
        streamMode = intent.getStringExtra(EXTRA_MODE) ?: "strong"

        if (streamUrl.isBlank()) { finish(); return }

        buildUI()
        initializePlayer()
    }

    private fun buildUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setShowNextButton(false)
            setShowPreviousButton(false)
        }
        root.addView(playerView)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xAA000000.toInt())
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(48), dp(48)))

        val nameText = TextView(this).apply {
            text = streamName
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        topBar.addView(nameText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(topBar)

        progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        root.addView(progressBar)

        errorText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
            setTextColor(Color.parseColor("#ff6b6b"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        root.addView(errorText)

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun initializePlayer() {
        val loadControl = if (streamMode == "weak") {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(2000, 20000, 800, 1500)
                .setTargetBufferBytes(20 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 120000, 2500, 5000)
                .setTargetBufferBytes(512 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        }

        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("StarScreensApp/1.0")
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { exo ->
                playerView.player = exo

                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                progressBar.visibility = View.VISIBLE
                                errorText.visibility = View.GONE
                            }
                            Player.STATE_READY -> {
                                progressBar.visibility = View.GONE
                                errorText.visibility = View.GONE
                            }
                            else -> { }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        progressBar.visibility = View.GONE
                        errorText.visibility = View.VISIBLE
                        errorText.text = "تعذر تشغيل البث\n${error.errorCodeName}\nإعادة المحاولة خلال 5 ثواني..."
                        errorText.postDelayed({
                            if (!isFinishing && !isDestroyed) {
                                try { exo.prepare(); exo.play() } catch (_: Exception) {}
                            }
                        }, 5_000)
                    }
                })

                exo.setMediaSource(buildMediaSource(streamUrl, dataSourceFactory))
                exo.prepare()
                exo.playWhenReady = true
            }
    }

    private fun buildMediaSource(url: String, factory: DataSource.Factory): MediaSource {
        val lower = url.lowercase()
        val item = MediaItem.fromUri(url)
        return when {
            lower.contains(".m3u8") ->
                HlsMediaSource.Factory(factory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(item)
            lower.contains(".mpd") ->
                DashMediaSource.Factory(factory).createMediaSource(item)
            lower.contains(".ism") || lower.contains("/manifest") ->
                SsMediaSource.Factory(factory).createMediaSource(item)
            lower.startsWith("rtsp://") ->
                RtspMediaSource.Factory().createMediaSource(item)
            else ->
                ProgressiveMediaSource.Factory(factory).createMediaSource(item)
        }
    }

    override fun onPause() { super.onPause(); player?.pause() }
    override fun onResume() { super.onResume(); player?.play() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
