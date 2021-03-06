/*
 * Copyright (C) 2017 The LineageOS Project
 * Copyright (C) 2021 The XPerience Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mx.xperience.Yunikon.webview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.ProgressBar
import androidx.collection.ArrayMap
import mx.xperience.Yunikon.ui.UrlBarController
import mx.xperience.Yunikon.utils.PrefsUtils
import mx.xperience.Yunikon.utils.UrlUtils
import java.util.regex.Pattern

class WebViewExt : WebView {
    private val mRequestHeaders: MutableMap<String, String> = ArrayMap()
    private var mActivity: WebViewExtActivity? = null
    private var mMobileUserAgent: String? = null
    private var mDesktopUserAgent: String? = null
    var isIncognito = false
        private set
    private var mDesktopMode = false
    var lastLoadedUrl: String? = null
        private set

    constructor(context: Context?) : super(context!!) {}
    constructor(context: Context?, attrs: AttributeSet?) : super(context!!, attrs) {}
    constructor(context: Context?, attrs: AttributeSet?, defStyle: Int) : super(context!!, attrs, defStyle) {}

    override fun loadUrl(url: String) {
        lastLoadedUrl = url
        followUrl(url)
    }

    fun followUrl(url: String?) {
        var fixedUrl = url?.let { UrlUtils.smartUrlFilter(it) }
        if (fixedUrl != null) {
            super.loadUrl(fixedUrl, mRequestHeaders)
            return
        }
        val templateUri = mActivity?.let { PrefsUtils.getSearchEngine(it) }
        fixedUrl = UrlUtils.getFormattedUri(templateUri, url)
        if (fixedUrl != null) {
            super.loadUrl(fixedUrl, mRequestHeaders)
        }
    }

    private fun setup() {
        settings.javaScriptEnabled = PrefsUtils.getJavascript(mActivity)
        settings.javaScriptCanOpenWindowsAutomatically = PrefsUtils.getJavascript(mActivity)
        settings.setGeolocationEnabled(PrefsUtils.getLocation(mActivity))
        settings.setSupportMultipleWindows(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setAppCacheEnabled(!isIncognito)
        settings.databaseEnabled = !isIncognito
        settings.domStorageEnabled = !isIncognito
        settings.setAppCachePath(mActivity!!.getDir("appcache", Context.MODE_PRIVATE).path)
        setOnLongClickListener(object : OnLongClickListener {
            var shouldAllowDownload = false
            override fun onLongClick(v: View): Boolean {
                val result = hitTestResult
                when (result.type) {
                    HitTestResult.IMAGE_TYPE, HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                        shouldAllowDownload = true
                        mActivity!!.showSheetMenu(result.extra, shouldAllowDownload)
                        shouldAllowDownload = false
                        return true
                    }
                    HitTestResult.SRC_ANCHOR_TYPE -> {
                        mActivity!!.showSheetMenu(result.extra, shouldAllowDownload)
                        shouldAllowDownload = false
                        return true
                    }
                }
                return false
            }
        })
        setDownloadListener { url: String?, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long -> mActivity!!.downloadFileAsk(url, contentDisposition, mimeType) }

        // Mobile: Remove "wv" from the WebView's user agent. Some websites don't work
        // properly if the browser reports itself as a simple WebView.
        // Desktop: Generate the desktop user agent starting from the mobile one so that
        // we always report the current engine version.
        val pattern = Pattern.compile("([^)]+ \\()([^)]+)(\\) .*)")
        val matcher = pattern.matcher(settings.userAgentString)
        if (matcher.matches()) {
            val mobileDevice = matcher.group(2).replace("; wv", "")
            mMobileUserAgent = matcher.group(1) + mobileDevice + matcher.group(3)
            mDesktopUserAgent = matcher.group(1) + DESKTOP_DEVICE + matcher.group(3)
                    .replace(" Mobile ", " ")
            settings.userAgentString = mMobileUserAgent
        } else {
            Log.e(TAG, "Couldn't parse the user agent")
            mMobileUserAgent = settings.userAgentString
            mDesktopUserAgent = DESKTOP_USER_AGENT_FALLBACK
        }
        if (PrefsUtils.getDoNotTrack(mActivity)) {
            mRequestHeaders[HEADER_DNT] = "1"
        }
    }

    fun init(activity: WebViewExtActivity?, urlBarController: UrlBarController?,
             progressBar: ProgressBar?, incognito: Boolean) {
        mActivity = activity
        isIncognito = incognito
        val chromeClient = ChromeClient(activity!!, incognito,
                urlBarController!!, progressBar!!)
        webChromeClient = chromeClient
        webViewClient = WebClient(urlBarController)
        setup()
    }

    val snap: Bitmap
        get() {
            measure(MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            layout(0, 0, measuredWidth, measuredHeight)
            isDrawingCacheEnabled = true
            buildDrawingCache()
            val size = if (measuredWidth > measuredHeight) measuredHeight else measuredWidth
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            val height = bitmap.height
            canvas.drawBitmap(bitmap, 0f, height.toFloat(), paint)
            draw(canvas)
            return bitmap
        }
    var isDesktopMode: Boolean
        get() = mDesktopMode
        set(desktopMode) {
            mDesktopMode = desktopMode
            val settings = settings
            settings.userAgentString = if (desktopMode) mDesktopUserAgent else mMobileUserAgent
            settings.useWideViewPort = desktopMode
            settings.loadWithOverviewMode = desktopMode
            reload()
        }
    val requestHeaders: Map<String, String>
        get() = mRequestHeaders

    companion object {
        private const val TAG = "WebViewExt"
        private const val DESKTOP_DEVICE = "X11; Linux x86_64"
        private const val DESKTOP_USER_AGENT_FALLBACK = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/37.0.2049.0 Safari/537.36"
        private const val HEADER_DNT = "DNT"
    }
}