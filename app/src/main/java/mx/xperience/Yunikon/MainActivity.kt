/*
 * Copyright (C) 2017-2019 The LineageOS Project
 * Copyright (C) 2019-2021 The XPerience Project
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
package mx.xperience.Yunikon

import android.Manifest
import android.app.Activity
import android.app.ActivityManager.TaskDescription
import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.*
import android.preference.PreferenceManager
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient.CustomViewCallback
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuPopupHelper
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import mx.xperience.Yunikon.favorite.FavoriteActivity
import mx.xperience.Yunikon.favorite.FavoriteProvider
import mx.xperience.Yunikon.history.HistoryActivity
import mx.xperience.Yunikon.suggestions.SuggestionsAdapter
import mx.xperience.Yunikon.ui.SearchBarController
import mx.xperience.Yunikon.ui.UrlBarController
import mx.xperience.Yunikon.utils.IntentUtils
import mx.xperience.Yunikon.utils.PrefsUtils
import mx.xperience.Yunikon.utils.TabUtils
import mx.xperience.Yunikon.utils.UiUtils
import mx.xperience.Yunikon.webview.WebViewCompat.isThemeColorSupported
import mx.xperience.Yunikon.webview.WebViewExt
import mx.xperience.Yunikon.webview.WebViewExtActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference

class MainActivity : WebViewExtActivity(), SearchBarController.OnCancelListener {
    private lateinit var mCoordinator: CoordinatorLayout
    private lateinit var mAppBar: AppBarLayout
    private lateinit var mWebViewContainer: FrameLayout
    private lateinit var mWebView: WebViewExt
    private val mUrlResolvedReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!intent.hasExtra(Intent.EXTRA_INTENT) ||
                    !intent.hasExtra(Intent.EXTRA_RESULT_RECEIVER)) {
                return
            }
            val resolvedIntent: Intent = intent.getParcelableExtra(Intent.EXTRA_INTENT)!!
            if (TextUtils.equals(packageName, resolvedIntent.getPackage())) {
                val url: String = intent.getStringExtra(IntentUtils.EXTRA_URL)!!
                mWebView.loadUrl(url)
            } else {
                startActivity(resolvedIntent)
            }
            val receiver: ResultReceiver = intent.getParcelableExtra(Intent.EXTRA_RESULT_RECEIVER)!!
            receiver.send(RESULT_CANCELED, Bundle())
        }
    }
    private lateinit var mLoadingProgress: ProgressBar
    private lateinit var mSearchController: SearchBarController
    private lateinit var mToolbarSearchBar: RelativeLayout
    private var mHasThemeColorSupport = false
    private var mLastActionBarDrawable: Drawable? = null
    private var mThemeColor = 0
    private var mWaitingDownloadUrl: String? = null
    private var mUrlIcon: Bitmap? = null
    private val mUiModeChangeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            setUiMode()
        }
    }
    private var mIncognito = false
    private var mCustomView: View? = null
    private var mFullScreenCallback: CustomViewCallback? = null
    private var mSearchActive = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        mCoordinator = findViewById(R.id.coordinator_layout)
        mAppBar = findViewById(R.id.app_bar_layout)
        mWebViewContainer = findViewById(R.id.web_view_container)
        mLoadingProgress = findViewById(R.id.load_progress)
        mToolbarSearchBar = findViewById(R.id.toolbar_search_bar)
        val autoCompleteTextView = findViewById<AutoCompleteTextView>(R.id.url_bar)
        autoCompleteTextView.setAdapter(SuggestionsAdapter(this))
        autoCompleteTextView.setOnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                UiUtils.hideKeyboard(autoCompleteTextView)
                mWebView.loadUrl(autoCompleteTextView.text.toString())
                autoCompleteTextView.clearFocus()
                return@setOnEditorActionListener true
            }
            false
        }
        autoCompleteTextView.setOnKeyListener { v: View?, keyCode: Int, event: KeyEvent? ->
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                UiUtils.hideKeyboard(autoCompleteTextView)
                mWebView.loadUrl(autoCompleteTextView.text.toString())
                autoCompleteTextView.clearFocus()
                return@setOnKeyListener true
            }
            false
        }
        autoCompleteTextView.onItemClickListener = OnItemClickListener { adapterView: AdapterView<*>?, view: View, pos: Int, l: Long ->
            val searchString = (view.findViewById<View>(R.id.title) as TextView).text
            val url = searchString.toString()
            UiUtils.hideKeyboard(autoCompleteTextView)
            autoCompleteTextView.clearFocus()
            mWebView.loadUrl(url)
        }

        // Make sure prefs are set before loading them
        PreferenceManager.setDefaultValues(this, R.xml.settings, false)
        val intent = intent
        var url = intent.dataString
        mIncognito = intent.getBooleanExtra(IntentUtils.EXTRA_INCOGNITO, false)
        var desktopMode = false
        mIncognito = when (PrefsUtils.getIncognitoPolicy(this)) {
            ALWAYS_DEFAULT_TO_INCOGNITO -> true
            EXTERNAL_DEFAULT_TO_INCOGNITO -> Intent.ACTION_MAIN != intent.action
            else -> intent.getBooleanExtra(IntentUtils.EXTRA_INCOGNITO, false)
        }

        // Restore from previous instance
        if (savedInstanceState != null) {
            mIncognito = savedInstanceState.getBoolean(IntentUtils.EXTRA_INCOGNITO, mIncognito)
            if (url == null || url.isEmpty()) {
                url = savedInstanceState.getString(IntentUtils.EXTRA_URL, null)
            }
            desktopMode = savedInstanceState.getBoolean(IntentUtils.EXTRA_DESKTOP_MODE, false)
            mThemeColor = savedInstanceState.getInt(STATE_KEY_THEME_COLOR, 0)
        }
        if (mIncognito) {
            autoCompleteTextView.imeOptions = autoCompleteTextView.imeOptions or
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }

        // Listen for local broadcasts
        registerLocalBroadcastListeners()
        setUiMode()
        val incognitoIcon = findViewById<ImageView>(R.id.incognito)
        incognitoIcon.visibility = if (mIncognito) View.VISIBLE else View.GONE
        setupMenu()
        val urlBarController = UrlBarController(autoCompleteTextView,
                findViewById(R.id.secure))
        mWebView = findViewById(R.id.web_view)
        mWebView.init(this, urlBarController, mLoadingProgress, mIncognito)
        mWebView.isDesktopMode = desktopMode
        url ?: PrefsUtils.getHomePage(this)?.let { mWebView.loadUrl(it) }
        mHasThemeColorSupport = isThemeColorSupported(mWebView)
        val mm= this.mWebView
        mSearchController = mm?.let {
            SearchBarController(it,
                findViewById(R.id.search_menu_edit),
                findViewById(R.id.search_status),
                findViewById(R.id.search_menu_prev),
                findViewById(R.id.search_menu_next),
                findViewById(R.id.search_menu_cancel),
                this)
        }
        applyThemeColor(mThemeColor)
        try {
            val httpCacheDir = File(cacheDir, "suggestion_responses")
            val httpCacheSize = (1024 * 1024).toLong() // 1 MiB
            HttpResponseCache.install(httpCacheDir, httpCacheSize)
        } catch (e: IOException) {
            Log.i(TAG, "HTTP response cache installation failed:$e")
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(mUrlResolvedReceiver, IntentFilter(IntentUtils.EVENT_URL_RESOLVED))
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        unregisterReceiver(mUrlResolvedReceiver)
        val cache = HttpResponseCache.getInstalled()
        cache?.flush()
        super.onStop()
    }

    public override fun onPause() {
        mWebView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        mWebView.onResume()
        CookieManager.getInstance()
                .setAcceptCookie(!mWebView.isIncognito && PrefsUtils.getCookie(this))
        if (PrefsUtils.getLookLock(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onDestroy() {
        // Unregister the local broadcast receiver because the activity is being trashed
        unregisterLocalBroadcastsListeners()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (mSearchActive) {
            mSearchController!!.onCancel()
        } else if (mCustomView != null) {
            onHideCustomView()
        } else if (mWebView.canGoBack()) {
            mWebView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        when (requestCode) {
            LOCATION_PERM_REQ -> if (hasLocationPermission()) {
                mWebView.reload()
            }
            STORAGE_PERM_REQ -> if (hasStoragePermission() && mWaitingDownloadUrl != null) {
                downloadFileAsk(mWaitingDownloadUrl, null, null)
            } else {
                if (shouldShowRequestPermissionRationale(
                                Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    AlertDialog.Builder(this)
                            .setTitle(R.string.permission_error_title)
                            .setMessage(R.string.permission_error_storage)
                            .setCancelable(false)
                            .setPositiveButton(getString(R.string.permission_error_ask_again)
                            ) { _: DialogInterface?, _: Int -> requestStoragePermission() }
                            .setNegativeButton(getString(R.string.dismiss)
                            ) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
                            .show()
                } else {
                    mCoordinator?.let {
                        Snackbar.make(it, getString(R.string.permission_error_forever),
                                Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }


    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Preserve webView status
        outState.putString(IntentUtils.EXTRA_URL, mWebView.url)
        outState.putBoolean(IntentUtils.EXTRA_INCOGNITO, mWebView.isIncognito)
        outState.putBoolean(IntentUtils.EXTRA_DESKTOP_MODE, mWebView.isDesktopMode)
        outState.putInt(STATE_KEY_THEME_COLOR, mThemeColor)
    }

    private fun setupMenu() {
        val menu = findViewById<ImageButton>(R.id.search_menu)
        menu.setOnClickListener { v: View? ->
            val isDesktop = mWebView.isDesktopMode
            val wrapper = ContextThemeWrapper(this,
                    R.style.AppTheme_PopupMenuOverlapAnchor)
            val popupMenu = PopupMenu(wrapper, menu, Gravity.NO_GRAVITY,
                    R.attr.actionOverflowMenuStyle, 0)
            popupMenu.inflate(R.menu.menu_main)
            val desktopMode = popupMenu.menu.findItem(R.id.desktop_mode)
            desktopMode.title = getString(if (isDesktop) R.string.menu_mobile_mode else R.string.menu_desktop_mode)
            desktopMode.icon = ContextCompat.getDrawable(this, if (isDesktop) R.drawable.ic_mobile else R.drawable.ic_desktop)
            popupMenu.setOnMenuItemClickListener { item: MenuItem ->
                when (item.itemId) {
                    R.id.menu_new -> TabUtils.openInNewTab(this, null, false)
                    R.id.menu_incognito -> TabUtils.openInNewTab(this, null, true)
                    R.id.menu_reload -> mWebView.reload()
                    R.id.menu_add_favorite -> setAsFavorite(mWebView.title, mWebView.url)
                    R.id.menu_share ->                         // Delay a bit to allow popup menu hide animation to play
                        Handler().postDelayed({ shareUrl(mWebView.url) }, 300)
                    R.id.menu_search ->                         // Run the search setup
                        showSearch()
                    R.id.menu_favorite -> startActivity(Intent(this, FavoriteActivity::class.java))
                    R.id.menu_history -> startActivity(Intent(this, HistoryActivity::class.java))
                    R.id.menu_shortcut -> addShortcut()
                    R.id.menu_print -> {
                        val printManager = getSystemService(PrintManager::class.java)
                        val documentName = "Yunikon document"
                        val printAdapter = mWebView.createPrintDocumentAdapter(documentName)
                        printManager.print(documentName, printAdapter,
                                PrintAttributes.Builder().build())
                    }
                    R.id.desktop_mode -> {
                        mWebView.isDesktopMode = !isDesktop
                        desktopMode.title = getString(if (isDesktop) R.string.menu_desktop_mode else R.string.menu_mobile_mode)
                        desktopMode.icon = ContextCompat.getDrawable(this, if (isDesktop) R.drawable.ic_desktop else R.drawable.ic_mobile)
                    }
                    R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                }
                true
            }

            // Fuck you, lint
            val helper = MenuPopupHelper(wrapper,
                    (popupMenu.menu as MenuBuilder), menu)
            helper.setForceShowIcon(true)
            helper.show()
        }
    }

    private fun showSearch() {
        mToolbarSearchBar.visibility = View.GONE
        findViewById<View>(R.id.toolbar_search_page).visibility = View.VISIBLE
        mSearchController!!.onShow()
        mSearchActive = true
    }

    override fun onCancelSearch() {
        findViewById<View>(R.id.toolbar_search_page).visibility = View.GONE
        mToolbarSearchBar.visibility = View.VISIBLE
        mSearchActive = false
    }

    private fun shareUrl(url: String?) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_TEXT, url)
        if (PrefsUtils.getAdvancedShare(this) && url == mWebView.url) {
            val file = File(cacheDir, System.currentTimeMillis().toString() + ".png")
            try {
                FileOutputStream(file).use { out ->
                    val bm = mWebView.snap ?: return
                    bm.compress(Bitmap.CompressFormat.PNG, 70, out)
                    out.flush()
                    out.close()
                    intent.putExtra(Intent.EXTRA_STREAM,
                            FileProvider.getUriForFile(this, PROVIDER, file))
                    intent.type = "image/png"
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: IOException) {
                Log.e(TAG, "${e.message}", e)
            }
        } else {
            intent.type = "text/plain"
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun setAsFavorite(title: String?, url: String?) {
        val hasValidIcon = mUrlIcon != null && !mUrlIcon!!.isRecycled
        var color = if (hasValidIcon) UiUtils.getColor(mUrlIcon, false) else Color.TRANSPARENT
        if (color == Color.TRANSPARENT) {
            color = ContextCompat.getColor(this, R.color.colorAccent)
        }
        SetAsFavoriteTask(contentResolver, title!!, url!!, color, mCoordinator).execute()
        //SetAsFavoriteTask(contentResolver, title?, url?, color, mCoordinator).execute()
    }

    override fun downloadFileAsk(url: String?, contentDisposition: String?, mimeType: String?) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        if (!hasStoragePermission()) {
            mWaitingDownloadUrl = url
            requestStoragePermission()
            return
        }
        mWaitingDownloadUrl = null
        AlertDialog.Builder(this)
                .setTitle(R.string.download_title)
                .setMessage(getString(R.string.download_message, fileName))
                .setPositiveButton(getString(R.string.download_positive)
                ) { dialog: DialogInterface?, which: Int -> url?.let { fetchFile(it, fileName) } }
                .setNegativeButton(getString(R.string.dismiss)
                ) { dialog: DialogInterface, which: Int -> dialog.dismiss() }
                .show()
    }

    private fun fetchFile(url: String, fileName: String) {
        val request: DownloadManager.Request
        request = try {
            DownloadManager.Request(Uri.parse(url))
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Cannot download non http or https scheme")
            return
        }

        // Let this downloaded file be scanned by MediaScanner - so that it can
        // show up in Gallery app, for example.
        request.allowScanningByMediaScanner()
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        request.setMimeType(MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(url)))
        getSystemService(DownloadManager::class.java).enqueue(request)
    }

    override fun showSheetMenu(url: String?, shouldAllowDownload: Boolean) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_actions, LinearLayout(this))
        val tabLayout = view.findViewById<View>(R.id.sheet_new_tab)
        val shareLayout = view.findViewById<View>(R.id.sheet_share)
        val favouriteLayout = view.findViewById<View>(R.id.sheet_favourite)
        val downloadLayout = view.findViewById<View>(R.id.sheet_download)
        tabLayout.setOnClickListener { v: View? ->
            TabUtils.openInNewTab(this, url, mIncognito)
            sheet.dismiss()
        }
        shareLayout.setOnClickListener { v: View? ->
            shareUrl(url)
            sheet.dismiss()
        }
        favouriteLayout.setOnClickListener { v: View? ->
            setAsFavorite(url, url)
            sheet.dismiss()
        }
        if (shouldAllowDownload) {
            downloadLayout.setOnClickListener { v: View? ->
                downloadFileAsk(url, null, null)
                sheet.dismiss()
            }
            downloadLayout.visibility = View.VISIBLE
        }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun requestStoragePermission() {
        val permissionArray = arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        requestPermissions(permissionArray, STORAGE_PERM_REQ)
    }

    private fun hasStoragePermission(): Boolean {
        val result = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return result == PackageManager.PERMISSION_GRANTED
    }

    override fun requestLocationPermission() {
        val permissionArray = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        requestPermissions(permissionArray, LOCATION_PERM_REQ)
    }

    override fun hasLocationPermission(): Boolean {
        val result = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        return result == PackageManager.PERMISSION_GRANTED
    }

    override fun onThemeColorSet(color: Int) {
        if (mHasThemeColorSupport) {
            applyThemeColor(color)
        }
    }

    override fun onFaviconLoaded(favicon: Bitmap?) {
        if (favicon == null || favicon.isRecycled) {
            return
        }
        mUrlIcon = favicon.copy(favicon.config, true)
        if (!mHasThemeColorSupport) {
            applyThemeColor(UiUtils.getColor(favicon, mWebView.isIncognito))
        }
        if (!favicon.isRecycled) {
            favicon.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyThemeColor(color: Int) {
        var color = color
        val hasValidColor = color != Color.TRANSPARENT
        mThemeColor = color
        color = themeColorWithFallback
        val actionBar = supportActionBar
        if (actionBar != null) {
            val newDrawable = ColorDrawable(color)
            if (mLastActionBarDrawable != null) {
                val layers = arrayOf(mLastActionBarDrawable!!, newDrawable)
                val transition = TransitionDrawable(layers)
                transition.isCrossFadeEnabled = true
                transition.startTransition(200)
                actionBar.setBackgroundDrawable(transition)
            } else {
                actionBar.setBackgroundDrawable(newDrawable)
            }
            mLastActionBarDrawable = newDrawable
            val window = window
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = color
            window.navigationBarColor = color
        }
        val progressColor = if (hasValidColor) if (UiUtils.isColorLight(color)) Color.BLACK else Color.WHITE else ContextCompat.getColor(this, R.color.colorAccent)
        mLoadingProgress.progressTintList = ColorStateList.valueOf(progressColor)
        mLoadingProgress.postInvalidate()
        val isReachMode = UiUtils.isReachModeEnabled(this)
        if (isReachMode) {
            window.navigationBarColor = color
        } else {
            window.statusBarColor = color
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                if (UiUtils.isColorLight(color)) {
                    if (isReachMode) {
                        it.setSystemBarsAppearance(APPEARANCE_LIGHT_NAVIGATION_BARS,
                                APPEARANCE_LIGHT_NAVIGATION_BARS)
                    } else {
                        it.setSystemBarsAppearance(APPEARANCE_LIGHT_STATUS_BARS,
                                APPEARANCE_LIGHT_STATUS_BARS)
                    }
                } else {
                    if (isReachMode) {
                        it.setSystemBarsAppearance(0, APPEARANCE_LIGHT_NAVIGATION_BARS)
                    } else {
                        it.setSystemBarsAppearance(0, APPEARANCE_LIGHT_STATUS_BARS)
                    }
                }
            }
        } else {
            var flags = window.decorView.systemUiVisibility
            flags = if (UiUtils.isColorLight(color)) {
                flags or if (isReachMode) {
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                } else {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            } else {
                flags and if (isReachMode) {
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                } else {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
            }
            window.decorView.systemUiVisibility = flags
        }
        setTaskDescription(TaskDescription(mWebView.title,
                mUrlIcon, color))
    }

    @Suppress("DEPRECATION")
    private fun resetSystemUIColor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.setSystemBarsAppearance(0, APPEARANCE_LIGHT_NAVIGATION_BARS)
                it.setSystemBarsAppearance(0, APPEARANCE_LIGHT_STATUS_BARS)
            }
        } else {
            var flags = window.decorView.systemUiVisibility
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            window.decorView.systemUiVisibility = flags
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }

    private val themeColorWithFallback: Int
        private get() = if (mThemeColor != Color.TRANSPARENT) {
            mThemeColor
        } else ContextCompat.getColor(this,
                if (mWebView.isIncognito) R.color.colorIncognito else R.color.colorPrimary)

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (mCustomView != null) {
            if (callback != null) {
                callback.onCustomViewHidden()
            }
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mCustomView = view
        mFullScreenCallback = callback
        setImmersiveMode(true)
        mCustomView!!.setBackgroundColor(ContextCompat.getColor(this, android.R.color.black))
        addContentView(mCustomView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        mAppBar.visibility = View.GONE
        mWebViewContainer.visibility = View.GONE
    }

    override fun onHideCustomView() {
        if (mCustomView == null) {
            return
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setImmersiveMode(false)
        mAppBar.visibility = View.VISIBLE
        mWebViewContainer.visibility = View.VISIBLE
        val viewGroup = mCustomView!!.parent as ViewGroup
        viewGroup.removeView(mCustomView)
        mFullScreenCallback!!.onCustomViewHidden()
        mFullScreenCallback = null
        mCustomView = null
    }

    private fun addShortcut() {
        val intent = Intent(this, MainActivity::class.java)
        intent.data = Uri.parse(mWebView.url)
        intent.action = Intent.ACTION_MAIN
        val launcherIcon: Icon
        launcherIcon = if (mUrlIcon != null) {
            Icon.createWithBitmap(
                    UiUtils.getShortcutIcon(mUrlIcon!!, themeColorWithFallback))
        } else {
            Icon.createWithResource(this, R.mipmap.ic_launcher)
        }
        val title = mWebView.title
        val shortcutInfo = ShortcutInfo.Builder(this, title)
                .setShortLabel(title!!)
                .setIcon(launcherIcon)
                .setIntent(intent)
                .build()
        getSystemService(ShortcutManager::class.java).requestPinShortcut(shortcutInfo, null)
    }

    @Suppress("DEPRECATION")
    private fun setImmersiveMode(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(!enable)
            window.insetsController?.let {
                val flags = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                val behavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (enable) {
                    it.hide(flags)
                    it.systemBarsBehavior = behavior
                } else {
                    it.show(flags)
                    it.systemBarsBehavior = behavior.inv()
                }
            }
        } else {
            var flags = window.decorView.systemUiVisibility
            val immersiveModeFlags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            flags = if (enable) {
                flags or immersiveModeFlags
            } else {
                flags and immersiveModeFlags.inv()
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        setImmersiveMode(hasFocus && mCustomView != null)
    }

    private fun registerLocalBroadcastListeners() {
        val manager = LocalBroadcastManager.getInstance(this)
        if (!UiUtils.isTablet(this)) {
            manager.registerReceiver(mUiModeChangeReceiver, IntentFilter(IntentUtils.EVENT_CHANGE_UI_MODE))
        }
    }

    private fun unregisterLocalBroadcastsListeners() {
        val manager = LocalBroadcastManager.getInstance(this)
        if (!UiUtils.isTablet(this)) {
            manager.unregisterReceiver(mUiModeChangeReceiver)
        }
    }

    private fun setUiMode() {
        // Now you don't see it
        mCoordinator!!.alpha = 0f
        // Magic happens
        changeUiMode(UiUtils.isReachModeEnabled(this))
        // Now you see it
        mCoordinator!!.alpha = 1f
    }

    private fun changeUiMode(isReachMode: Boolean) {
        val appBarParams = mAppBar.layoutParams as CoordinatorLayout.LayoutParams
        val containerParams = mWebViewContainer.layoutParams as CoordinatorLayout.LayoutParams
        val progressParams = mLoadingProgress.layoutParams as RelativeLayout.LayoutParams
        val searchBarParams = mToolbarSearchBar.layoutParams as RelativeLayout.LayoutParams
        val margin = UiUtils.getDimenAttr(this, R.style.AppTheme,
                android.R.attr.actionBarSize).toInt()
        if (isReachMode) {
            appBarParams.gravity = Gravity.BOTTOM
            containerParams.setMargins(0, 0, 0, margin)
            progressParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            progressParams.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE)
            searchBarParams.removeRule(RelativeLayout.ABOVE)
            searchBarParams.addRule(RelativeLayout.BELOW, R.id.load_progress)
        } else {
            appBarParams.gravity = Gravity.TOP
            containerParams.setMargins(0, margin, 0, 0)
            progressParams.removeRule(RelativeLayout.ALIGN_PARENT_TOP)
            progressParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
            searchBarParams.removeRule(RelativeLayout.BELOW)
            searchBarParams.addRule(RelativeLayout.ABOVE, R.id.load_progress)
        }
        mAppBar.layoutParams = appBarParams
        mAppBar.invalidate()
        mWebViewContainer.layoutParams = containerParams
        mWebViewContainer.invalidate()
        mLoadingProgress.layoutParams = progressParams
        mLoadingProgress.invalidate()
        mToolbarSearchBar.layoutParams = searchBarParams
        mToolbarSearchBar.invalidate()
        resetSystemUIColor()
        if (mThemeColor != 0) {
            applyThemeColor(mThemeColor)
        }
    }

    private class SetAsFavoriteTask constructor(
            private val contentResolver: ContentResolver,
            private val title: String,
            private val url: String,
            private val color: Int, parentView: View?
    ) : AsyncTask<Void?, Void?, Boolean>() {
        private val parentView = WeakReference(parentView)
        override fun doInBackground(vararg params: Void?): Boolean {
            FavoriteProvider.addOrUpdateItem(contentResolver, title, url, color)
            return true
        }

        override fun onPostExecute(aBoolean: Boolean) {
            val view = parentView.get()
            if (view != null) {
                Snackbar.make(view, view.context.getString(R.string.favorite_added),
                        Snackbar.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val PROVIDER = "mx.xperience.Yunikon.fileprovider"
        private const val STATE_KEY_THEME_COLOR = "theme_color"
        private const val STORAGE_PERM_REQ = 423
        private const val LOCATION_PERM_REQ = 424
        private const val ALWAYS_DEFAULT_TO_INCOGNITO = 1
        private const val EXTERNAL_DEFAULT_TO_INCOGNITO = 2
    }
}