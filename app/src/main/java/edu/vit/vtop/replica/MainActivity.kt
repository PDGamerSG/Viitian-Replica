package edu.vit.vtop.replica

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private var dashboardOpened = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.topAppBar))

        progressBar = findViewById(R.id.pageLoadProgress)
        swipeRefreshLayout = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        swipeRefreshLayout.setOnRefreshListener { webView.reload() }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString VITianReplica/1.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()

                if (scheme == "http" || scheme == "https") {
                    return false
                }

                if (scheme == "intent") {
                    return try {
                        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        startActivity(intent)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                swipeRefreshLayout.isRefreshing = false
                if (!dashboardOpened) {
                    maybeOpenDashboard(url)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    swipeRefreshLayout.isRefreshing = false
                    Toast.makeText(
                        this@MainActivity,
                        R.string.network_error_message,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.isVisible = newProgress in 1..99
                progressBar.progress = newProgress
            }
        }

        if (savedInstanceState == null) {
            webView.loadUrl(LOGIN_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
    }

    private fun openDashboard() {
        if (dashboardOpened) {
            return
        }
        dashboardOpened = true
        CookieManager.getInstance().flush()
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    private fun maybeOpenDashboard(url: String) {
        if (!url.lowercase().contains("vtop.vit.ac.in/vtop/")) {
            return
        }

        webView.evaluateJavascript(LOGIN_STATE_SCRIPT) { rawState ->
            if (parseJsResult(rawState) == AUTH_STATE_AUTHENTICATED) {
                openDashboard()
            }
        }
    }

    private fun parseJsResult(rawResult: String?): String {
        return rawResult
            ?.trim()
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.replace("\\\\", "\\")
            ?.replace("\\\"", "\"")
            ?.lowercase()
            .orEmpty()
    }

    private companion object {
        private const val LOGIN_URL = "https://vtop.vit.ac.in/vtop/login"
        private const val AUTH_STATE_AUTHENTICATED = "authenticated"
        private const val LOGIN_STATE_SCRIPT =
            """
            (function () {
              const path = (window.location.pathname || '').toLowerCase();
              const hasRoleForms = !!document.querySelector('#stdForm,#empForm,#parentForm,#alumniForm,form[action*="/prelogin/setup"]');
              const hasCredentialForm = !!document.querySelector('#vtopLoginForm,input[type="password"],form[action*="/login"],form[action*="/doLogin"]');
              if (
                hasRoleForms ||
                hasCredentialForm ||
                path.includes('/vtop/login') ||
                path.includes('/vtop/prelogin') ||
                path.includes('/vtop/initialprocess')
              ) {
                return 'prelogin';
              }
              return path.includes('/vtop/') ? 'authenticated' : 'prelogin';
            })();
            """
    }
}

