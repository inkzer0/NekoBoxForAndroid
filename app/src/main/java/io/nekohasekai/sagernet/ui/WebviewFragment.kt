package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import moe.matsuri.nb4a.utils.WebViewUtil

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    lateinit var mWebView: WebView
    private var dashboardLoadedForConnection: Boolean? = null
    private var bundledDashboard: BundledClashDashboard? = null
    private var webViewClosed = false

    fun onControllerStateChanged(connected: Boolean) {
        if (!::mWebView.isInitialized || webViewClosed || view == null) return
        if (dashboardLoadedForConnection?.let { it != connected } == true) loadDashboard()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // layout
        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        val binding = LayoutWebviewBinding.bind(view)

        // webview
        WebView.setWebContentsDebuggingEnabled(false)
        mWebView = binding.webview
        webViewClosed = false
        mWebView.settings.domStorageEnabled = true
        mWebView.settings.javaScriptEnabled = true
        mWebView.settings.allowFileAccess = false
        mWebView.settings.allowContentAccess = false
        // Do not forward Chromium console messages that can include WebSocket URLs.
        mWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage) = true
        }
        loadDashboard()
    }

    private fun loadDashboard() {
        if (webViewClosed || view == null) return
        val url = DataStore.yacdURL
        val bundled = if (BundledClashDashboard.isConfiguredUrl(url)) {
            bundledDashboard ?: BundledClashDashboard(requireContext().applicationContext)
                .also { bundledDashboard = it }
        } else null
        dashboardLoadedForConnection = if (bundled == null) null else DataStore.serviceState.connected
        mWebView.stopLoading()
        mWebView.webViewClient = object : WebViewClient() {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                bundled?.let { !it.allowsNavigation(Uri.parse(url)) } ?: false

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                bundled?.let { !request.isForMainFrame || !it.allowsNavigation(request.url) } ?: false

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                bundled?.intercept(request)

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
            }
        }
        mWebView.loadUrl(if (bundled != null) BundledClashDashboard.URL else url)
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_set_url -> {
                val view = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(DataStore.yacdURL)
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        DataStore.yacdURL = view.text.toString()
                        loadDashboard()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.close -> closeWebView()
        }
        return true
    }

    override fun onDestroyView() {
        closeWebView()
        super.onDestroyView()
    }

    private fun closeWebView() {
        if (!::mWebView.isInitialized || webViewClosed) return
        webViewClosed = true
        dashboardLoadedForConnection = null
        bundledDashboard = null
        mWebView.stopLoading()
        mWebView.onPause()
        (mWebView.parent as? android.view.ViewGroup)?.removeView(mWebView)
        mWebView.destroy()
    }
}
