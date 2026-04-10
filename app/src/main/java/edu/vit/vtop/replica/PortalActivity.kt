package edu.vit.vtop.replica

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class PortalActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private val mainHandler = Handler(Looper.getMainLooper())

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingRouteSpec: RouteSpec? = null
    private var routeNavigationAttempts = 0
    private var routeNavigationInProgress = false
    private var loginRedirectHandled = false
    private lateinit var action: String

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileUploadCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
            )
            fileUploadCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_portal)
        setSupportActionBar(findViewById(R.id.topAppBar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_PORTAL_HOME
        supportActionBar?.title = titleForAction(action)
        pendingRouteSpec = routeForAction(action)

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
                handlePageFinished(url)
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
                        this@PortalActivity,
                        R.string.network_error_message,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (
                    request.isForMainFrame &&
                    errorResponse.statusCode == 404 &&
                    request.url.toString().lowercase().contains("vtop.vit.ac.in/vtop/")
                ) {
                    swipeRefreshLayout.isRefreshing = false
                    recoverFromNotFoundPage()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.isVisible = newProgress in 1..99
                progressBar.progress = newProgress
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                return try {
                    val chooserIntent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    fileChooserLauncher.launch(chooserIntent)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileUploadCallback = null
                    Toast.makeText(
                        this@PortalActivity,
                        R.string.file_chooser_not_found,
                        Toast.LENGTH_SHORT,
                    ).show()
                    false
                }
            }
        }

        webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    CookieManager.getInstance().getCookie(url)?.let { cookie ->
                        addRequestHeader("Cookie", cookie)
                    }
                    setTitle(fileName)
                    setDescription(getString(R.string.download_started))
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                    )
                }
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
            },
        )

        if (savedInstanceState == null) {
            webView.loadUrl(initialUrlForAction())
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }

            R.id.action_home -> {
                pendingRouteSpec = null
                routeNavigationAttempts = 0
                webView.loadUrl(HOME_URL)
                return true
            }

            R.id.action_refresh -> {
                webView.reload()
                return true
            }

            R.id.action_open_external -> {
                openExternal(Uri.parse(webView.url ?: HOME_URL))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        webView.setDownloadListener(null)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
    }

    private fun handlePageFinished(currentUrl: String) {
        if (loginRedirectHandled) {
            return
        }
        if (!currentUrl.lowercase().contains("vtop.vit.ac.in/vtop/")) {
            return
        }

        checkSessionState { sessionState ->
            if (sessionState == SESSION_PRELOGIN) {
                redirectToLogin()
            } else {
                triggerRouteNavigation()
            }
        }
    }

    private fun checkSessionState(onResolved: (String) -> Unit) {
        webView.evaluateJavascript(SESSION_STATE_SCRIPT) { rawResult ->
            val parsed = parseJsResult(rawResult)
            if (parsed == SESSION_PRELOGIN) {
                onResolved(SESSION_PRELOGIN)
            } else {
                onResolved(SESSION_AUTHENTICATED)
            }
        }
    }

    private fun redirectToLogin() {
        if (loginRedirectHandled) {
            return
        }
        loginRedirectHandled = true
        Toast.makeText(this, R.string.login_required_message, Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        finish()
    }

    private fun triggerRouteNavigation() {
        val routeSpec = pendingRouteSpec ?: return
        if (routeNavigationInProgress) {
            return
        }
        if (routeNavigationAttempts >= MAX_ROUTE_NAV_ATTEMPTS) {
            pendingRouteSpec = null
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        routeNavigationInProgress = true
        routeNavigationAttempts += 1
        webView.evaluateJavascript(buildRouteNavigationScript(routeSpec, routeNavigationAttempts)) { rawResult ->
            routeNavigationInProgress = false
            val result = parseJsResult(rawResult)
            when (result) {
                ROUTE_NAV_DONE -> pendingRouteSpec = null
                ROUTE_NAV_NOT_FOUND_PAGE -> recoverFromNotFoundPage()
                else -> {
                    mainHandler.postDelayed(
                        { triggerRouteNavigation() },
                        ROUTE_NAV_RETRY_DELAY_MS,
                    )
                }
            }
        }
    }

    private fun recoverFromNotFoundPage() {
        routeNavigationInProgress = false
        if (pendingRouteSpec == null) {
            mainHandler.post { webView.loadUrl(HOME_URL) }
            return
        }
        if (routeNavigationAttempts >= MAX_ROUTE_NAV_ATTEMPTS) {
            pendingRouteSpec = null
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        mainHandler.postDelayed(
            { webView.loadUrl(HOME_URL) },
            ROUTE_NAV_RETRY_DELAY_MS,
        )
    }

    private fun buildRouteNavigationScript(routeSpec: RouteSpec, attemptNumber: Int): String {
        val routeTokensLiteral = toJsArrayLiteral(routeSpec.routeTokens)
        val textTermsLiteral = toJsArrayLiteral(routeSpec.textTerms)
        val fallbackPathsLiteral = toJsArrayLiteral(routeSpec.fallbackPaths, lowercase = false)
        val menuIdsLiteral = toJsArrayLiteral(routeSpec.menuIds)
        return """
            (function () {
              const routeTokens = $routeTokensLiteral;
              const textTerms = $textTermsLiteral;
              const fallbackPaths = $fallbackPathsLiteral;
              const menuIds = $menuIdsLiteral;
              const attemptNumber = $attemptNumber;
              const normalize = (value) => (value || '').toString().toLowerCase().trim();
              const normalizePath = (value) => normalize(value).replace(/^\/+/, '');
              const notFoundSignals = [
                'http status 404',
                '404 not found',
                'page not found',
                'error 404',
                'whitelabel error page',
                'requested resource is not available',
                'the origin server did not find a current representation',
                'resource not found',
                'requested url was not found',
              ];
              const softFallbackSignals = [
                'vtop translates to',
                'session timed out',
              ];

              const getDocuments = () => {
                const docs = [document];
                Array.from(document.querySelectorAll('iframe,frame')).forEach(frame => {
                  try {
                    const frameDoc = frame.contentDocument || (frame.contentWindow && frame.contentWindow.document);
                    if (frameDoc) {
                      docs.push(frameDoc);
                    }
                  } catch (_) {
                    // Ignore inaccessible cross-origin frames.
                  }
                });
                return docs;
              };

              const hasSoftFallbackPage = (doc) => {
                const bodyText = normalize((doc.body && (doc.body.innerText || doc.body.textContent)) || '');
                const titleText = normalize(doc.title || '');
                const hasFallbackForm = !!doc.querySelector('#stdForm,form[action*="/prelogin/setup"]');
                const hasSessionExpiredView = bodyText.includes('session timed out') && !!doc.querySelector('form[action*="/session/expired/out"]');
                const hasFallbackSignal = softFallbackSignals.some(signal => bodyText.includes(signal));
                return hasFallbackForm || hasSessionExpiredView || hasFallbackSignal || (titleText.includes('vtop vellore') && bodyText.includes('vtop translates to'));
              };

              const hasNotFoundContent = (doc) => {
                const bodyText = normalize((doc.body && (doc.body.innerText || doc.body.textContent)) || '');
                if (!bodyText) {
                  return false;
                }
                return notFoundSignals.some(signal => bodyText.includes(signal));
              };

              const docs = getDocuments();
              if (docs.some(doc => hasNotFoundContent(doc) || hasSoftFallbackPage(doc))) {
                return 'not_found_page';
              }

              const currentPath = normalizePath(window.location.pathname + window.location.search);
              if (routeTokens.some(token => currentPath.includes(token))) {
                return 'done';
              }

              const hasRouteToken = (value) => {
                const normalized = normalizePath(value);
                return routeTokens.some(token => normalized.includes(token));
              };

              const hasTextTerm = (value) => {
                const normalized = normalize(value);
                return textTerms.some(term => normalized.includes(term));
              };

              const hasMenuId = (value) => {
                const normalized = normalize(value);
                if (!normalized) {
                  return false;
                }
                return menuIds.some(menuId => normalized === menuId || normalized.endsWith(menuId));
              };

              const candidates = [];
              const addCandidate = (node, score) => {
                if (!node || score <= 0) {
                  return;
                }
                const clickable = node.closest('a,button,[role="button"],[onclick],[data-url]') || node;
                if (!clickable || typeof clickable.click !== 'function') {
                  return;
                }
                const existing = candidates.find(candidate => candidate.node === clickable);
                if (existing) {
                  if (score > existing.score) {
                    existing.score = score;
                  }
                  return;
                }
                candidates.push({ node: clickable, score });
              };

              docs.forEach(doc => {
                Array.from(doc.querySelectorAll('[id]')).forEach(node => {
                  if (hasMenuId(node.getAttribute('id'))) {
                    addCandidate(node, 18);
                  }
                });

                Array.from(doc.querySelectorAll('[data-url],a[href],button,[role="button"],[onclick],li,div,span')).forEach(node => {
                  const dataUrl = node.getAttribute('data-url');
                  const href = node.getAttribute('href');
                  const onClick = node.getAttribute('onclick');
                  const id = node.getAttribute('id');
                  const text = node.innerText || node.textContent;
                  let score = 0;

                  if (hasRouteToken(dataUrl) || hasRouteToken(href) || hasRouteToken(onClick)) {
                    score += 14;
                  }
                  if (hasMenuId(id)) {
                    score += 10;
                  }
                  if (normalize(onClick).includes('loadmydiv(')) {
                    score += 6;
                  }
                  if (hasTextTerm(text)) {
                    score += 8;
                  }

                  if (score > 0) {
                    addCandidate(node, score);
                  }
                });
              });

              if (candidates.length > 0) {
                candidates.sort((a, b) => b.score - a.score);
                const best = candidates[0];
                if (best && typeof best.node.click === 'function') {
                  best.node.click();
                  return 'clicked';
                }
              }

              const fallbackOffset = fallbackPaths.length > 0
                ? (Math.max(attemptNumber, 1) - 1) % fallbackPaths.length
                : 0;
              const orderedFallbacks = fallbackPaths
                .slice(fallbackOffset)
                .concat(fallbackPaths.slice(0, fallbackOffset))
                .filter(path => !!path);

              const fallbackForCurrentPath = orderedFallbacks.find(path => {
                const normalizedPath = normalizePath(path);
                return normalizedPath && !currentPath.includes(normalizedPath);
              });

              const loadMyDivHost = (() => {
                if (typeof window.loadmydiv === 'function') {
                  return window;
                }
                if (window.parent && typeof window.parent.loadmydiv === 'function') {
                  return window.parent;
                }
                return null;
              })();
              if (loadMyDivHost && fallbackForCurrentPath) {
                try {
                  loadMyDivHost.loadmydiv(fallbackForCurrentPath.replace(/^\/+/, ''));
                  return 'clicked';
                } catch (_) {
                  // Try direct URL fallback below.
                }
              }

              if (fallbackForCurrentPath) {
                window.location.assign('/vtop/' + fallbackForCurrentPath.replace(/^\/+/, ''));
                return 'navigated';
              }

              return 'not_found';
            })();
        """.trimIndent()
    }

    private fun routeForAction(action: String): RouteSpec? {
        return when (action) {
            ACTION_MARKS -> RouteSpec(
                routeTokens = MARKS_ROUTE_TOKENS,
                textTerms = MARKS_TEXT_TERMS,
                fallbackPaths = MARKS_FALLBACK_PATHS,
                menuIds = MARKS_MENU_IDS,
            )
            ACTION_ATTENDANCE -> RouteSpec(
                routeTokens = ATTENDANCE_ROUTE_TOKENS,
                textTerms = ATTENDANCE_TEXT_TERMS,
                fallbackPaths = ATTENDANCE_FALLBACK_PATHS,
                menuIds = ATTENDANCE_MENU_IDS,
            )
            ACTION_DA_UPLOAD -> RouteSpec(
                routeTokens = DA_UPLOAD_ROUTE_TOKENS,
                textTerms = DA_UPLOAD_TEXT_TERMS,
                fallbackPaths = DA_UPLOAD_FALLBACK_PATHS,
                menuIds = DA_UPLOAD_MENU_IDS,
            )
            else -> null
        }
    }

    private fun initialUrlForAction(): String {
        return HOME_URL
    }

    private fun titleForAction(action: String): String {
        return when (action) {
            ACTION_MARKS -> getString(R.string.dashboard_marks_title)
            ACTION_ATTENDANCE -> getString(R.string.dashboard_attendance_title)
            ACTION_DA_UPLOAD -> getString(R.string.dashboard_da_upload_title)
            else -> getString(R.string.portal_title)
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

    private fun toJsArrayLiteral(values: List<String>, lowercase: Boolean = true): String {
        return values
            .asSequence()
            .map { if (lowercase) it.lowercase() else it }
            .distinct()
            .joinToString(prefix = "[", postfix = "]") { "'${escapeForJs(it)}'" }
    }

    private data class RouteSpec(
        val routeTokens: List<String>,
        val textTerms: List<String>,
        val fallbackPaths: List<String>,
        val menuIds: List<String>,
    )

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.unable_to_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_ACTION = "portal_action"

        const val ACTION_PORTAL_HOME = "portal_home"
        const val ACTION_MARKS = "marks"
        const val ACTION_ATTENDANCE = "attendance"
        const val ACTION_DA_UPLOAD = "da_upload"

        private const val BASE_URL = "https://vtop.vit.ac.in/vtop/"
        private const val HOME_URL = BASE_URL + "content"

        private val MARKS_ROUTE_TOKENS = listOf(
            "examinations/studentmarkview",
            "examinations/studentmark",
            "examinations/markview",
            "examinations/examgradeview/studentgradeview",
            "examinations/examgradeview/studentgradehistory",
            "academics/common/studentmarkview",
            "academics/common/studentmark",
        )
        private val MARKS_TEXT_TERMS = listOf(
            "marks",
            "mark view",
            "grade",
            "grade view",
            "grade history",
            "exam result",
        )
        private val MARKS_FALLBACK_PATHS = listOf(
            "examinations/StudentMarkView",
            "examinations/studentmarkview",
            "examinations/examGradeView/StudentGradeView",
            "examinations/examGradeView/StudentGradeHistory",
            "academics/common/studentmarkview",
        )
        private val MARKS_MENU_IDS = listOf("EXM0011")

        private val ATTENDANCE_ROUTE_TOKENS = listOf(
            "academics/common/studentattendance",
            "academics/studentattendance",
            "academics/common/attendance",
        )
        private val ATTENDANCE_TEXT_TERMS = listOf(
            "attendance",
            "class attendance",
            "attendance report",
        )
        private val ATTENDANCE_FALLBACK_PATHS = listOf(
            "academics/common/StudentAttendance",
            "academics/common/studentattendance",
            "academics/studentattendance",
        )
        private val ATTENDANCE_MENU_IDS = listOf("ACD0042")

        private val DA_UPLOAD_ROUTE_TOKENS = listOf(
            "examinations/studentda",
            "examinations/daupload",
            "examinations/digitalassignment",
            "examinations/processdigitalassignment",
            "academics/common/studentda",
        )
        private val DA_UPLOAD_TEXT_TERMS = listOf(
            "da upload",
            "digital assignment",
            "assignment upload",
            "digital assignment upload",
        )
        private val DA_UPLOAD_FALLBACK_PATHS = listOf(
            "examinations/StudentDA",
            "examinations/studentda",
            "examinations/processDigitalAssignment",
            "examinations/daupload",
            "academics/common/studentda",
        )
        private val DA_UPLOAD_MENU_IDS = listOf("EXM0017")

        private const val ROUTE_NAV_DONE = "done"
        private const val ROUTE_NAV_NOT_FOUND_PAGE = "not_found_page"
        private const val MAX_ROUTE_NAV_ATTEMPTS = 12
        private const val ROUTE_NAV_RETRY_DELAY_MS = 700L

        private const val SESSION_PRELOGIN = "prelogin"
        private const val SESSION_AUTHENTICATED = "authenticated"
        private const val SESSION_STATE_SCRIPT =
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

