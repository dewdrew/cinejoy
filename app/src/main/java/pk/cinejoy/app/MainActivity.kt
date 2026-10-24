package pk.cinejoy.app

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature

/**
 * Single activity that serves both form factors from one APK.
 *
 * The device class is resolved once at startup and drives orientation, system bars,
 * input handling and the user-agent string. Everything else — the WebView engine,
 * the ad shield, HTML5 fullscreen video and lifecycle handling — is shared.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var cursorView: ImageView
    private lateinit var errorLayout: LinearLayout
    private lateinit var errorTitle: TextView
    private lateinit var retryButton: Button

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var tvCursorController: TvCursorController? = null

    private var isTvDevice = false
    private var lastBackPressTime = 0L

    /** Last URL the main frame started loading, used to scope error reporting. */
    private var lastMainFrameUrl: String? = null

    companion object {
        private const val HOME_URL = "https://cinejoy.pk/"

        /** Window in which a second Back press exits, instead of on the first. */
        private const val BACK_EXIT_WINDOW_MS = 2_000L

        /** Seek distance for the remote's fast-forward / rewind keys. */
        private const val SEEK_STEP_SECONDS = 10

        /**
         * Desktop Chrome UA for TV. Fire OS ships an old WebView whose stock UA makes
         * several embedded players serve a mobile layout with touch-only controls.
         */
        private const val TV_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Movie playback can run for hours with no input.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        progressBar = findViewById(R.id.progress_bar)
        cursorView = findViewById(R.id.cursor_view)
        errorLayout = findViewById(R.id.error_layout)
        errorTitle = findViewById(R.id.error_title)
        retryButton = findViewById(R.id.retry_button)

        retryButton.setOnClickListener { retryLoad() }

        isTvDevice = detectTvDevice()
        if (isTvDevice) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hideSystemBars()
            tvCursorController = TvCursorController(webView, cursorView) { activeTouchTarget() }
                .also { it.initializeForTv() }
        }

        configureWebView()
        configureServiceWorkerAdBlocking()
        setupBackNavigation()

        val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
        if (!restored) {
            webView.loadUrl(HOME_URL)
        }
    }

    /**
     * Fire TV and Android TV both report the television UI mode, but some Fire OS
     * builds only answer through the feature flags, so all three signals are checked.
     */
    private fun detectTvDevice(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }

    /**
     * The view a synthetic tap from the remote should land on. The offline overlay and
     * the fullscreen video container both sit above the WebView, and a touch event
     * dispatched straight at the WebView would otherwise pass underneath them.
     */
    private fun activeTouchTarget(): View = when {
        errorLayout.visibility == View.VISIBLE -> errorLayout
        customView != null -> fullscreenContainer
        else -> webView
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            // Embedded players start their own playback; requiring a gesture here
            // would stall them behind a tap the remote cannot deliver.
            mediaPlaybackRequiresUserGesture = false

            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT

            // Multiple windows must be supported for onCreateWindow to be consulted,
            // which is where popup ads get dropped.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false

            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false

            // Nothing in the app reads local files; closing these off costs nothing.
            allowFileAccess = false
            allowContentAccess = false

            userAgentString = if (isTvDevice) {
                TV_USER_AGENT
            } else {
                // Some players reject the WebView marker outright.
                userAgentString.replace("; wv", "")
            }
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (AdBlocker.isAdDomain(url)) {
                    return AdBlocker.createEmptyResource()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = shouldBlockNavigation(request?.url?.toString())

            // Fire OS 5 runs API 22, where only the String overload is called.
            @Suppress("DEPRECATION", "OverridingDeprecatedMember")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                shouldBlockNavigation(url)

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                lastMainFrameUrl = url
                hideErrorOverlay()
                progressBar.progress = 0
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
                if (isTvDevice) {
                    injectTvFocusStyles()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Sub-resource failures are routine on ad-heavy pages and must not
                // replace a page that rendered fine.
                if (request?.isForMainFrame == true) {
                    showErrorOverlay()
                }
            }

            @Suppress("DEPRECATION", "OverridingDeprecatedMember")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (failingUrl != null && failingUrl == lastMainFrameUrl) {
                    showErrorOverlay()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            /**
             * Drop `window.open()` and `target="_blank"` popups. Returning false
             * discards the request without navigating, so an ad cannot replace the
             * player the viewer is watching.
             */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean = false

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    /**
     * Route service-worker traffic through the ad shield too.
     *
     * Requests a service worker issues never reach
     * [WebViewClient.shouldInterceptRequest] — they go through a separate controller —
     * so without this the blocklist silently misses them. cinejoy.pk registers a
     * service worker, which makes this the difference between partial and full
     * coverage.
     *
     * The controller is process-global, so this is set once rather than per WebView.
     */
    private fun configureServiceWorkerAdBlocking() {
        // Fire OS 5 (API 22) and other old WebViews have no service-worker support at
        // all, so there is nothing to intercept. getInstance() throws when the feature
        // is missing, hence the check first.
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(
                WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST
            )
        ) {
            return
        }

        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
            object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url?.toString() ?: return null
                    return if (AdBlocker.isAdDomain(url)) {
                        AdBlocker.createEmptyResource()
                    } else {
                        null
                    }
                }
            }
        )
    }

    /** True when navigation to [url] must be refused. */
    private fun shouldBlockNavigation(url: String?): Boolean {
        if (url.isNullOrBlank()) return true

        val scheme = try {
            Uri.parse(url).scheme?.lowercase()
        } catch (_: Exception) {
            null
        }

        // intent://, market://, tel:, mailto: and friends would hand the viewer off to
        // Silk or the Appstore, with no way back on a remote.
        if (scheme != "http" && scheme != "https") return true

        return AdBlocker.isAdDomain(url)
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback?) {
        customView = view
        customViewCallback = callback

        fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        fullscreenContainer.visibility = View.VISIBLE
        webView.visibility = View.GONE

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
    }

    private fun exitFullscreen() {
        val view = customView ?: return

        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null

        customViewCallback?.onCustomViewHidden()
        customViewCallback = null

        webView.visibility = View.VISIBLE
        webView.requestFocus()

        // A TV stays landscape and immersive; a phone goes back to the user's choice.
        if (!isTvDevice) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showSystemBars()
        }
    }

    /**
     * Give focusable page elements a visible outline. Streaming templates frequently
     * ship no focus styling at all, which makes D-pad traversal invisible.
     */
    private fun injectTvFocusStyles() {
        val script = """
            (function() {
                var id = 'cinejoy-tv-focus-style';
                if (document.getElementById(id)) { return; }
                var style = document.createElement('style');
                style.id = id;
                style.appendChild(document.createTextNode(
                    'a:focus, button:focus, input:focus, select:focus, textarea:focus,' +
                    ' [tabindex]:focus { outline: 3px solid #E50914 !important;' +
                    ' outline-offset: 2px !important; }'
                ));
                (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Transport keys are consumed on both DOWN and UP so Fire OS does not also
        // route them to its own media session.
        if (isMediaTransportKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) {
                handleMediaTransportKey(event.keyCode)
            }
            return true
        }

        if (isTvDevice && tvCursorController?.handleKeyEvent(event) == true) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun isMediaTransportKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND -> true

        else -> false
    }

    private fun handleMediaTransportKey(keyCode: Int) {
        val action = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                "if (v.paused) { v.play(); } else { v.pause(); }"

            KeyEvent.KEYCODE_MEDIA_PLAY -> "v.play();"

            KeyEvent.KEYCODE_MEDIA_PAUSE -> "v.pause();"

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                "var ahead = v.currentTime + $SEEK_STEP_SECONDS;" +
                    "v.currentTime = isFinite(v.duration) ? Math.min(v.duration, ahead) : ahead;"

            KeyEvent.KEYCODE_MEDIA_REWIND ->
                "v.currentTime = Math.max(0, v.currentTime - $SEEK_STEP_SECONDS);"

            else -> return
        }
        runOnActiveVideo(action)
    }

    /**
     * Run [body] against the page's active `<video>`, picking whichever element is
     * playing and, failing that, the longest one — a reasonable stand-in for "the
     * movie" on pages that also host short trailers.
     *
     * Only the main frame is reachable; a player inside a cross-origin iframe handles
     * its own keys.
     */
    private fun runOnActiveVideo(body: String) {
        val script = """
            (function() {
                var videos = document.querySelectorAll('video');
                var target = null;
                for (var i = 0; i < videos.length; i++) {
                    var candidate = videos[i];
                    if (target === null) { target = candidate; continue; }
                    if (!candidate.paused && target.paused) { target = candidate; continue; }
                    if (candidate.paused === target.paused &&
                        (candidate.duration || 0) > (target.duration || 0)) {
                        target = candidate;
                    }
                }
                if (target === null) { return; }
                var v = target;
                $body
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()

                    webView.canGoBack() -> webView.goBack()

                    else -> {
                        // Back is the most-pressed key on a Fire TV remote; one stray
                        // press should not close a movie.
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastBackPressTime < BACK_EXIT_WINDOW_MS) {
                            finish()
                        } else {
                            lastBackPressTime = now
                            Toast.makeText(
                                this@MainActivity,
                                R.string.press_back_again,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
    }

    private fun showErrorOverlay() {
        // The body text covers the common case; the heading separates "no network"
        // from "the site did not answer".
        errorTitle.setText(
            if (isNetworkAvailable()) R.string.error_title_load_failed else R.string.error_title
        )
        progressBar.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
        retryButton.requestFocus()
    }

    private fun hideErrorOverlay() {
        if (errorLayout.visibility != View.GONE) {
            errorLayout.visibility = View.GONE
        }
    }

    private fun retryLoad() {
        hideErrorOverlay()
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE

        val current = webView.url
        val target = if (current.isNullOrBlank() || current == "about:blank") {
            lastMainFrameUrl ?: HOME_URL
        } else {
            current
        }
        webView.loadUrl(target)
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            manager.activeNetworkInfo?.isConnected == true
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity handles these changes itself, so the immersive flags and the
        // pointer bounds have to be re-applied by hand.
        if (isTvDevice || customView != null) {
            hideSystemBars()
        }
        tvCursorController?.onViewportChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.resumeTimers()
        webView.onResume()
        if (isTvDevice || customView != null) {
            hideSystemBars()
        }
    }

    override fun onDestroy() {
        tvCursorController?.release()
        tvCursorController = null

        if (customView != null) {
            fullscreenContainer.removeAllViews()
            customView = null
            customViewCallback = null
        }

        webView.stopLoading()
        webView.webChromeClient = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()

        super.onDestroy()
    }
}
