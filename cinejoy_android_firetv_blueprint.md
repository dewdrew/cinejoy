# Cinejoy (`https://cinejoy.pk/`) Universal Android & Amazon Fire TV App Blueprint

This technical blueprint covers the end-to-end architecture, source code, and deployment workflow to build a single, unified Android APK (`cinejoy-universal.apk`) that runs seamlessly on **Android phones/tablets** (touchscreen) and **Amazon Fire TV Stick / Android TV** (10-foot UI with D-pad remote navigation).

## 1. Architecture & System Overview

A single Android Studio project (Kotlin + Android WebView) detects the hardware environment at runtime and adapts its input, layout, and media playback behavior automatically.

| Feature / Layer | Android Phone & Tablet Behavior | Amazon Fire TV Stick / Android TV Behavior |
| :--- | :--- | :---
| **Launcher Integration** | Standard `MAIN` + `LAUNCHER` app icon | `LEANBACK_LAUNCHER` 320x180dp TV banner on Fire OS Home |
| **Hardware Declaration** | Touchscreen active; dynamic portrait/landscape | `android.hardware.touchscreen` marked `required="false"`; locked to landscape |
| **Input & Navigation** | Native touch scrolling, pinch-to-zoom disabled, swipe back | D-pad spatial navigation + toggleable Virtual Mouse Cursor (via Menu/Play button) |
| **Video Playback** | Hardware-accelerated HTML5 `<video>`, custom fullscreen `FrameLayout`, keep-screen-awake lock | Hardware-accelerated HTML5 `<video>`, auto-fullscreen media focus, D-pad media transport controls |
| **Ad & Popup Shield** | Blocks off-domain popups, ad-network iframes, and unwanted intent redirects | Prevents background browser launches that trap Fire Stick users outside the app |

## 2. Project Setup & Directory Structure

Create a new project in **Android Studio** using the **Empty Views Activity** template with the following parameters:
- **Name**: `Cinejoy`
- **Package name**: `pk.cinejoy.app`
- **Minimum SDK**: `API 21 (Android 5.0 Lollipop)` — ensures compatibility with older Fire TV Stick Gen 2, Fire TV Stick Lite, Fire TV Stick 4K, and all modern Android phones.
- **Language**: `Kotlin`
- **Build configuration language**: `Kotlin DSL (build.gradle.kts)`

```text
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/pk/cinejoy/app/
│   │   ├── MainActivity.kt
│   │   ├── AdBlocker.kt
│   │   └── TvCursorController.kt
│   └── res/
│       ├── drawable/
│       │   ├── tv_banner.png        (320x180px banner for Fire TV home row)
│       │   └── cursor_pointer.xml   (Vector drawable for Fire TV virtual mouse)
│       ├── layout/
│       │   └── activity_main.xml
│       └── values/
│           ├── strings.xml
│           └── themes.xml
└── build.gradle.kts
```

## 3. Step-by-Step Implementation Plan & Source Code

### Phase 1: Configure `build.gradle.kts` (Module `:app`)

Configure dependencies to support both standard Android AppCompat views and AndroidX WebKit features.

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pk.cinejoy.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "pk.cinejoy.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.leanback:leanback:1.0.0")
}
```

### Phase 2: Configure `AndroidManifest.xml` for Mobile + Fire TV

Fire TV Stick rejects or hides apps that require a touchscreen or lack a `LEANBACK_LAUNCHER` intent filter. The manifest below enables both mobile and TV launchers, enables hardware acceleration for smooth video decoding, and permits cleartext/HTTPS media streams.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="pk.cinejoy.app">

    <!-- Network & Media Permissions -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- CRITICAL FOR FIRE TV: Do not require touchscreen or phone hardware -->
    <uses-feature
        android:name="android.hardware.touchscreen"
        android:required="false" />
    <uses-feature
        android:name="android.software.leanback"
        android:required="false" />

    <application
        android:allowBackup="true"
        android:banner="@drawable/tv_banner"
        android:hardwareAccelerated="true"
        android:icon="@mipmap/ic_launcher"
        android:label="Cinejoy"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat.NoActionBar"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize|ScreenLayout|keyboardHidden|uiMode"
            android:exported="true"
            android:launchMode="singleTask">

            <!-- Standard Android Phone / Tablet Launcher -->
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!-- Amazon Fire TV / Android TV Leanback Launcher -->
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

### Phase 3: Create the Unified Layout (`res/layout/activity_main.xml`)

This layout includes:
1. The primary `WebView` for `https://cinejoy.pk/`.
2. A dedicated `fullscreen_container` (`FrameLayout`) so HTML5 video players can enter true fullscreen without crashing or clipping.
3. A `cursor_view` (`ImageView`) overlaid on top for Fire TV Stick users when navigating via D-pad remote.
4. A loading `ProgressBar`.

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root_layout"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#000000"
    android:keepScreenOn="true">

    <!-- Primary WebView -->
    <WebView
        android:id="@+id/webview"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:focusable="true"
        android:focusableInTouchMode="true" />

    <!-- HTML5 Video Fullscreen Target Container -->
    <FrameLayout
        android:id="@+id/fullscreen_container"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#000000"
        android:visibility="gone" />

    <!-- Loading Indicator -->
    <ProgressBar
        android:id="@+id/progress_bar"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="3dp"
        android:layout_gravity="top"
        android:indeterminate="false"
        android:max="100"
        android:progressTint="#E50914" />

    <!-- Virtual Mouse Cursor for Fire TV Stick Remote -->
    <ImageView
        android:id="@+id/cursor_view"
        android:layout_width="28dp"
        android:layout_height="28dp"
        android:src="@drawable/cursor_pointer"
        android:visibility="gone"
        android:elevation="12dp" />

</FrameLayout>
```

Create the cursor vector graphic at `res/drawable/cursor_pointer.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="28dp"
    android:height="28dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:strokeColor="#E50914"
        android:strokeWidth="1.5"
        android:pathData="M4,2 L4,20 L9,15 L13,22 L16,20.5 L12,13.5 L19,13.5 Z" />
</vector>
```

### Phase 4: Build the Ad & Popup Blocker (`AdBlocker.kt`)

Streaming sites often use third-party video embeds that trigger popup tabs or redirect attempts. On a Fire Stick, an external redirect opens the Silk Browser or fails silently. `AdBlocker.kt` keeps navigation strictly on `cinejoy.pk` and allows known CDN/video player streams while dropping popup ad hosts.

```kotlin
package pk.cinejoy.app

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {
    private val BLOCKED_DOMAINS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "adservice.google.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "adsterra.com",
        "exoclick.com",
        "juicyads.com",
        "clickadu.com",
        "hilltopads.net"
    )

    fun isAdDomain(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        return BLOCKED_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    fun createEmptyResource(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}
```

### Phase 5: Build the Fire TV Stick Remote Cursor Controller (`TvCursorController.kt`)

Streaming web pages and embedded video controls (play, pause, server selector, audio track, fullscreen button) are often difficult to reach with standard TV arrow keys alone. This controller turns the Fire TV Stick D-pad into a smooth on-screen pointer and dispatches synthetic touch clicks when the user presses the center **Select / OK** button. Pressing the **Menu** button on the Fire Stick remote toggles between **Pointer Mode** and **Scroll Mode**.

```kotlin
package pk.cinejoy.app

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

class TvCursorController(
    private val webView: WebView,
    private val cursorView: ImageView
) {
    var isCursorEnabled = true
        private set

    private var cursorX = 500f
    private var cursorY = 400f
    private val stepSize = 26f
    private val scrollStep = 140

    fun initializeForTv() {
        cursorView.visibility = View.VISIBLE
        cursorView.post {
            cursorX = (webView.width / 2f)
            cursorY = (webView.height / 2f)
            updateCursorPosition()
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        // Toggle between Cursor Mode and Page Scroll Mode using the Fire TV Menu key
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
            isCursorEnabled = !isCursorEnabled
            cursorView.visibility = if (isCursorEnabled) View.VISIBLE else View.GONE
            val modeText = if (isCursorEnabled) "Cursor Mode: ON" else "Scroll Mode: ON"
            Toast.makeText(webView.context, modeText, Toast.LENGTH_SHORT).show()
            return true
        }

        if (!isCursorEnabled) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    moveCursor(0f, -stepSize)
                    if (cursorY <= 40f) webView.scrollBy(0, -scrollStep)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    moveCursor(0f, stepSize)
                    if (cursorY >= webView.height - 40f) webView.scrollBy(0, scrollStep)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN) moveCursor(-stepSize, 0f)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) moveCursor(stepSize, 0f)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    performSyntheticClick(cursorX, cursorY)
                }
                return true
            }
        }
        return false
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX = max(0f, min(webView.width.toFloat() - cursorView.width, cursorX + dx))
        cursorY = max(0f, min(webView.height.toFloat() - cursorView.height, cursorY + dy))
        updateCursorPosition()
    }

    private fun updateCursorPosition() {
        cursorView.translationX = cursorX
        cursorView.translationY = cursorY
    }

    private fun performSyntheticClick(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis() + 50
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0)
        webView.dispatchTouchEvent(downEvent)
        webView.dispatchTouchEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()
    }
}
```

### Phase 6: Implement `MainActivity.kt` (WebView Engine, Video Fullscreen, & TV Detection)

`MainActivity.kt` ties everything together:
- Detects if the device is an Amazon Fire TV / Android TV via `UiModeManager`.
- Configures `WebSettings` for HTML5 streaming, DOM storage, cookies, and autoplay.
- Implements `WebChromeClient.onShowCustomView` and `onHideCustomView` so embedded movie players expand cleanly to fullscreen and lock to landscape orientation on phones.
- Blocks multi-window popup ads via `setSupportMultipleWindows(true)` without opening external windows.

```kotlin
package pk.cinejoy.app

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var cursorView: ImageView

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var tvCursorController: TvCursorController? = null
    private var isTvDevice = false

    companion object {
        private const val HOME_URL = "https://cinejoy.pk/"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake during movie playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        progressBar = findViewById(R.id.progress_bar)
        cursorView = findViewById(R.id.cursor_view)

        isTvDevice = detectTvDevice()
        if (isTvDevice) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hideSystemBars()
            tvCursorController = TvCursorController(webView, cursorView).also {
                it.initializeForTv()
            }
        }

        configureWebView()
        setupBackNavigation()

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun detectTvDevice(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(true) // Intercepts target="_blank" popup ads
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false

            // Use a clean desktop/modern mobile Chrome User-Agent to avoid legacy mobile redirects
            userAgentString = if (isTvDevice) {
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            } else {
                userAgentString.replace("; wv", "")
            }
        }

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
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                // Block known ad domains or non-http schemes (like intent:// or market://)
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return true
                }
                if (AdBlocker.isAdDomain(url)) {
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress == 100) {
                    progressBar.visibility = View.GONE
                }
            }

            // Block popup windows opened via window.open()
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                return false
            }

            // Handle HTML5 Video entering Fullscreen
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                hideSystemBars()
            }

            // Handle HTML5 Video exiting Fullscreen
            override fun onHideCustomView() {
                if (customView == null) return
                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE
                customView = null
                customViewCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
                if (!isTvDevice) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Play/Pause media key support on Fire Stick remote
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && event.action == KeyEvent.ACTION_UP) {
            webView.evaluateJavascript(
                "(function(){ var v = document.querySelector('video'); if(v){ if(v.paused) v.play(); else v.pause(); } })();",
                null
            )
            return true
        }

        if (isTvDevice && tvCursorController?.handleKeyEvent(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> {
                        webView.webChromeClient?.onHideCustomView()
                    }
                    webView.canGoBack() -> {
                        webView.goBack()
                    }
                    else -> {
                        finish()
                    }
                }
            }
        })
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
```

## 4. Packaging, Signing, and Hosting the APK on `cinejoy.pk`

1. **Create the Fire TV Banner**:
   - Place a `320x180` PNG image at `app/src/main/res/drawable/tv_banner.png` featuring the Cinejoy logo. Fire TV uses this rectangular banner in the "Your Apps & Channels" grid.
2. **Generate a Signed Release APK**:
   - In Android Studio, select **Build → Generate Signed Bundle / APK... → APK**.
   - Create a new Keystore (`.jks`), choose the `release` build variant, and check both **V1 (Jar Signature)** and **V2 (Full APK Signature)** for full compatibility across older Fire OS 5/6/7/8 devices and modern Android 14+ phones.
3. **Host the APK for Direct Download**:
   - Upload the signed `cinejoy-release.apk` directly to your web server (e.g., `https://cinejoy.pk/app/cinejoy.apk`).
   - Add a **"Download Android / Fire TV App"** button on your website header pointing to that URL.

## 5. Installing & Testing on Android & Amazon Fire TV Stick

### Installing on Android Phones & Tablets
1. Open `https://cinejoy.pk/app/cinejoy.apk` in Chrome or any mobile browser.
2. Tap **Download** and open the `.apk` file.
3. If prompted, allow **Install unknown apps** for the browser and tap **Install**.

### Sideloading on Amazon Fire TV Stick (2 Methods)

#### Method A: Via the "Downloader" App (Easiest for End Users)
1. On the Fire TV Stick, go to **Settings → My Fire TV → Developer Options**:
   - *Tip*: If **Developer Options** is hidden, go to **Settings → My Fire TV → About**, highlight **Fire TV Stick**, and press the **Select** button 7 times rapidly.
2. Enable **ADB Debugging** and **Install Unknown Apps** (for the **Downloader** app).
3. Install the free **Downloader** app by AFTVnews from the Amazon Appstore.
4. Open **Downloader** and enter your hosted URL: `https://cinejoy.pk/app/cinejoy.apk` (or generate a short 5-digit Downloader code at `go.aftvnews.com` pointing to your APK URL).
5. Click **Go** — once downloaded, the Fire Stick automatically prompts to **Install** the Cinejoy app.

#### Method B: Via Wireless ADB from Your Computer (During Development)
1. Find your Fire Stick's IP address under **Settings → My Fire TV → About → Network**.
2. In your computer terminal (or Android Studio Terminal), run:
   ```bash
   adb connect <FIRE_STICK_IP>:5555
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

## 6. Recommended Website Tweaks on `cinejoy.pk` for TV & App Users

To make the app feel even more like a native Netflix/Prime Video app when loaded inside the wrapper:
- **Highlight Focused Elements**: Add a CSS `:focus-visible` rule on `https://cinejoy.pk/` so movie posters and buttons glow when selected:
  ```css
  a:focus-visible, button:focus-visible, .movie-card:focus-visible {
      outline: 3px solid #E50914 !important;
      transform: scale(1.05);
      transition: transform 0.15s ease;
  }
  ```
- **In-App Version Check (Optional)**: Serve a tiny JSON file at `https://cinejoy.pk/app/version.json` (`{"versionCode": 2, "apkUrl": "https://cinejoy.pk/app/cinejoy.apk"}`) so the app can notify users when a new APK update is available.
