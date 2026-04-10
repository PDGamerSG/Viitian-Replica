package edu.vit.vtop.replica

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
    private val loginCredentialBridge = LoginCredentialBridge()
    private val credentialPreferences by lazy { getSharedPreferences(CREDENTIAL_PREFS, MODE_PRIVATE) }
    private var dashboardOpened = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.topAppBar))

        progressBar = findViewById(R.id.pageLoadProgress)
        swipeRefreshLayout = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        swipeRefreshLayout.setColorSchemeResources(R.color.marks_indicator)
        swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.marks_surface)
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
        webView.addJavascriptInterface(loginCredentialBridge, LOGIN_CREDENTIAL_BRIDGE_NAME)

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
                maybeAssistLoginForm(url)
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
        webView.removeJavascriptInterface(LOGIN_CREDENTIAL_BRIDGE_NAME)
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
    }

    private fun maybeAssistLoginForm(url: String) {
        if (!url.lowercase().contains("vtop.vit.ac.in/vtop/")) {
            return
        }
        val savedUsername = credentialPreferences.getString(CREDENTIAL_USERNAME_KEY, "").orEmpty()
        val savedPassword = credentialPreferences.getString(CREDENTIAL_PASSWORD_KEY, "").orEmpty()
        val script = buildLoginAssistScript(savedUsername, savedPassword)
        webView.evaluateJavascript(script, null)
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

    private fun escapeForJs(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
    }

    private fun buildLoginAssistScript(savedUsername: String, savedPassword: String): String {
        val usernameLiteral = escapeForJs(savedUsername)
        val passwordLiteral = escapeForJs(savedPassword)
        return """
            (function () {
              const savedUsername = '$usernameLiteral';
              const savedPassword = '$passwordLiteral';
              const trimValue = (value) => (value || '').toString().trim();
              const lower = (value) => trimValue(value).toLowerCase();

              const forms = Array.from(document.querySelectorAll('form'));
              const form =
                forms.find(candidate => !!candidate.querySelector('input[type="password"]')) ||
                document.querySelector('#vtopLoginForm');
              if (!form) {
                return 'no_form';
              }

              const inputs = Array.from(form.querySelectorAll('input'));
              const passwordField =
                inputs.find(input => lower(input.type) === 'password') || null;
              const usernameField =
                inputs.find(input => {
                  if (!input || input === passwordField) {
                    return false;
                  }
                  const type = lower(input.type || 'text');
                  const name = lower(input.name || '');
                  const id = lower(input.id || '');
                  if (type === 'hidden' || type === 'password') {
                    return false;
                  }
                  if (name.includes('captcha') || id.includes('captcha')) {
                    return false;
                  }
                  if (
                    name.includes('user') ||
                    id.includes('user') ||
                    name.includes('reg') ||
                    id.includes('reg')
                  ) {
                    return true;
                  }
                  return type === 'text' || type === 'email' || type === 'number' || type === 'tel';
                }) || null;

              const dispatchFieldEvents = (field) => {
                if (!field) {
                  return;
                }
                field.dispatchEvent(new Event('input', { bubbles: true }));
                field.dispatchEvent(new Event('change', { bubbles: true }));
              };

              if (usernameField && savedUsername && !trimValue(usernameField.value)) {
                usernameField.value = savedUsername;
                dispatchFieldEvents(usernameField);
              }
              if (passwordField && savedPassword && !trimValue(passwordField.value)) {
                passwordField.value = savedPassword;
                dispatchFieldEvents(passwordField);
              }

              const bridge = window.$LOGIN_CREDENTIAL_BRIDGE_NAME;
              if (!bridge || typeof bridge.onCredentialsCaptured !== 'function') {
                return 'filled';
              }

              const captureCredentials = () => {
                const usernameValue = trimValue((usernameField && usernameField.value) || '');
                const passwordValue = trimValue((passwordField && passwordField.value) || '');
                if (!usernameValue || !passwordValue) {
                  return;
                }
                bridge.onCredentialsCaptured(usernameValue, passwordValue);
              };

              if (!form.dataset.vitianCredentialHooked) {
                form.addEventListener('submit', captureCredentials, true);
                Array.from(form.querySelectorAll('button,input[type="submit"],input[type="button"]'))
                  .forEach(node => {
                    node.addEventListener('click', captureCredentials, true);
                  });
                form.dataset.vitianCredentialHooked = '1';
              }
              return 'done';
            })();
        """.trimIndent()
    }

    private inner class LoginCredentialBridge {
        @JavascriptInterface
        fun onCredentialsCaptured(username: String?, password: String?) {
            val cleanedUsername = username?.trim().orEmpty()
            val cleanedPassword = password?.trim().orEmpty()
            if (cleanedUsername.isBlank() || cleanedPassword.isBlank()) {
                return
            }

            val currentUsername = credentialPreferences.getString(CREDENTIAL_USERNAME_KEY, "")
            val currentPassword = credentialPreferences.getString(CREDENTIAL_PASSWORD_KEY, "")
            if (currentUsername == cleanedUsername && currentPassword == cleanedPassword) {
                return
            }

            credentialPreferences.edit()
                .putString(CREDENTIAL_USERNAME_KEY, cleanedUsername)
                .putString(CREDENTIAL_PASSWORD_KEY, cleanedPassword)
                .apply()
        }
    }

    private companion object {
        private const val LOGIN_URL = "https://vtop.vit.ac.in/vtop/login"
        private const val CREDENTIAL_PREFS = "vitian_saved_login"
        private const val CREDENTIAL_USERNAME_KEY = "username"
        private const val CREDENTIAL_PASSWORD_KEY = "password"
        private const val LOGIN_CREDENTIAL_BRIDGE_NAME = "AndroidLoginCredentialBridge"
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

