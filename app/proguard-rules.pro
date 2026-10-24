# Cinejoy release shrink rules.

# Keep WebView plumbing: the platform calls these reflectively.
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}

# Any future @JavascriptInterface bridge must survive obfuscation.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Leanback is pulled in for Fire TV / Android TV launcher support.
-dontwarn androidx.leanback.**

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
