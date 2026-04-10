package edu.vit.vtop.replica

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DaAssessmentsActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var assessmentList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var assessmentsAdapter: DaAssessmentsAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val blobDownloadBridge = BlobDownloadBridge()
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileUploadCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
            )
            fileUploadCallback = null
        }

    private var navigationAttempts = 0
    private var navigationInProgress = false
    private var courseOpenAttempts = 0
    private var courseOpenRequested = false
    private var assessmentsFetchInProgress = false
    private var assessmentFetchRetries = 0
    private var loginRedirectHandled = false

    private lateinit var targetClassId: String
    private lateinit var targetCourseCode: String
    private lateinit var targetCourseTitle: String
    private lateinit var targetCourseType: String
    private var targetSemesterValue: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_da_assessments)
        setSupportActionBar(findViewById(R.id.topAppBar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        targetClassId = intent.getStringExtra(EXTRA_CLASS_ID).orEmpty()
        targetCourseCode = intent.getStringExtra(EXTRA_COURSE_CODE).orEmpty()
        targetCourseTitle = intent.getStringExtra(EXTRA_COURSE_TITLE).orEmpty()
        targetCourseType = intent.getStringExtra(EXTRA_COURSE_TYPE).orEmpty()
        targetSemesterValue = intent.getStringExtra(EXTRA_SEMESTER_VALUE)?.ifBlank { null }
        supportActionBar?.title = buildToolbarTitle()

        progressBar = findViewById(R.id.pageLoadProgress)
        swipeRefreshLayout = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)
        assessmentList = findViewById(R.id.assessmentList)
        emptyText = findViewById(R.id.emptyText)

        assessmentsAdapter = DaAssessmentsAdapter()
        assessmentList.layoutManager = LinearLayoutManager(this)
        assessmentList.adapter = assessmentsAdapter

        swipeRefreshLayout.setColorSchemeResources(R.color.marks_indicator)
        swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.marks_surface)
        swipeRefreshLayout.setOnRefreshListener {
            navigationAttempts = 0
            courseOpenAttempts = 0
            courseOpenRequested = false
            assessmentFetchRetries = 0
            renderAssessments(emptyList())
            emptyText.text = getString(R.string.da_assessments_loading)
            webView.loadUrl(HOME_URL)
        }

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
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = "$userAgentString VITianReplica/1.0"
        }
        webView.addJavascriptInterface(blobDownloadBridge, BLOB_BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
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
                        this@DaAssessmentsActivity,
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
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
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
                        this@DaAssessmentsActivity,
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
                if (url.startsWith("blob:", ignoreCase = true)) {
                    val script = buildBlobDownloadScript(url, fileName, mimeType.orEmpty())
                    webView.evaluateJavascript(script, null)
                    return@DownloadListener
                }
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
            emptyText.text = getString(R.string.da_assessments_loading)
            renderAssessments(emptyList())
            webView.loadUrl(HOME_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }

        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        webView.stopLoading()
        webView.setDownloadListener(null)
        webView.removeJavascriptInterface(BLOB_BRIDGE_NAME)
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
            } else if (!courseOpenRequested) {
                ensureDaPageReady()
            } else {
                fetchAssessments()
            }
        }
    }

    private fun ensureDaPageReady() {
        if (navigationInProgress) {
            return
        }
        if (navigationAttempts >= MAX_NAVIGATION_ATTEMPTS) {
            emptyText.text = getString(R.string.da_assessments_unavailable)
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        navigationInProgress = true
        navigationAttempts += 1
        webView.evaluateJavascript(buildDaNavigationScript(navigationAttempts)) { rawResult ->
            navigationInProgress = false
            when (parseJsResult(rawResult)) {
                JS_STATE_DONE -> openTargetCourse()
                JS_STATE_PRELOGIN -> redirectToLogin()
                JS_STATE_NOT_FOUND_PAGE -> recoverFromNotFoundPage()
                else -> {
                    mainHandler.postDelayed(
                        { ensureDaPageReady() },
                        NAVIGATION_RETRY_DELAY_MS,
                    )
                }
            }
        }
    }

    private fun recoverFromNotFoundPage() {
        navigationInProgress = false
        if (navigationAttempts >= MAX_NAVIGATION_ATTEMPTS) {
            emptyText.text = getString(R.string.da_assessments_unavailable)
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        mainHandler.postDelayed(
            { webView.loadUrl(HOME_URL) },
            NAVIGATION_RETRY_DELAY_MS,
        )
    }

    private fun openTargetCourse() {
        if (targetClassId.isBlank()) {
            emptyText.text = getString(R.string.da_open_course_failed)
            return
        }
        if (courseOpenAttempts >= MAX_OPEN_COURSE_ATTEMPTS) {
            emptyText.text = getString(R.string.da_open_course_failed)
            Toast.makeText(this, R.string.da_open_course_failed, Toast.LENGTH_SHORT).show()
            return
        }

        courseOpenAttempts += 1
        val script = buildOpenCourseScript(targetClassId, targetSemesterValue)
        webView.evaluateJavascript(script) { rawResult ->
            when (parseJsResult(rawResult)) {
                "done",
                "submitted",
                "clicked",
                "changed",
                -> {
                    courseOpenRequested = true
                    assessmentFetchRetries = 0
                    mainHandler.postDelayed(
                        { fetchAssessments() },
                        ASSESSMENT_RETRY_DELAY_MS,
                    )
                }

                JS_STATE_PRELOGIN -> redirectToLogin()
                else -> {
                    mainHandler.postDelayed(
                        { openTargetCourse() },
                        OPEN_COURSE_RETRY_DELAY_MS,
                    )
                }
            }
        }
    }

    private fun fetchAssessments() {
        if (assessmentsFetchInProgress) {
            return
        }
        assessmentsFetchInProgress = true
        webView.evaluateJavascript(EXTRACT_DA_ASSESSMENTS_SCRIPT) { rawResult ->
            assessmentsFetchInProgress = false
            val parsed = parseJsResult(rawResult, lowercase = false)
            if (parsed.isBlank()) {
                if (scheduleAssessmentRetry()) {
                    return@evaluateJavascript
                }
                renderAssessments(emptyList())
                return@evaluateJavascript
            }

            val payload = runCatching { JSONObject(parsed) }.getOrNull()
            if (payload == null) {
                if (scheduleAssessmentRetry()) {
                    return@evaluateJavascript
                }
                renderAssessments(emptyList())
                return@evaluateJavascript
            }

            when (payload.optString("status")) {
                "ok" -> {
                    val payloadClassId = payload.optString("classId").trim()
                    if (
                        payloadClassId.isNotBlank() &&
                        targetClassId.isNotBlank() &&
                        !payloadClassId.equals(targetClassId, ignoreCase = true) &&
                        scheduleAssessmentRetry()
                    ) {
                        return@evaluateJavascript
                    }

                    val payloadCourseTitle = payload.optString("courseTitle").trim()
                    val payloadCourseType = payload.optString("courseType").trim()
                    if (payloadCourseTitle.isNotBlank()) {
                        targetCourseTitle = payloadCourseTitle
                    }
                    if (payloadCourseType.isNotBlank()) {
                        targetCourseType = payloadCourseType
                    }
                    supportActionBar?.title = buildToolbarTitle()

                    val entriesArray = payload.optJSONArray("entries")
                    val entries = buildList {
                        if (entriesArray != null) {
                            for (index in 0 until entriesArray.length()) {
                                val item = entriesArray.optJSONObject(index) ?: continue
                                add(
                                    DaAssessmentEntry(
                                        title = item.optString("title"),
                                        lastDate = item.optString("lastDate"),
                                        status = item.optString("status"),
                                        code = item.optString("code"),
                                        classId = item.optString("classId"),
                                        canDownloadQuestion = item.optBoolean("canDownloadQuestion"),
                                        canDownloadSubmission = item.optBoolean("canDownloadSubmission"),
                                        canUploadEdit = item.optBoolean("canUploadEdit"),
                                    ),
                                )
                            }
                        }
                    }

                    if (entries.isEmpty() && scheduleAssessmentRetry()) {
                        return@evaluateJavascript
                    }
                    assessmentFetchRetries = 0
                    renderAssessments(entries)
                }

                "prelogin" -> redirectToLogin()
                else -> {
                    if (scheduleAssessmentRetry()) {
                        return@evaluateJavascript
                    }
                    renderAssessments(emptyList())
                }
            }
        }
    }

    private fun scheduleAssessmentRetry(): Boolean {
        if (assessmentFetchRetries >= MAX_ASSESSMENT_FETCH_RETRIES) {
            return false
        }
        assessmentFetchRetries += 1
        emptyText.text = getString(R.string.da_assessments_loading)
        mainHandler.postDelayed(
            { fetchAssessments() },
            ASSESSMENT_RETRY_DELAY_MS,
        )
        return true
    }

    private fun renderAssessments(entries: List<DaAssessmentEntry>) {
        assessmentsAdapter.submit(entries)
        assessmentList.isVisible = entries.isNotEmpty()
        emptyText.isVisible = entries.isEmpty()
        emptyText.text = if (entries.isEmpty()) {
            getString(R.string.da_no_assessments)
        } else {
            getString(R.string.da_assessments_loading)
        }
    }

    private fun buildToolbarTitle(): String {
        val title = targetCourseTitle.trim()
        if (title.isBlank()) {
            return getString(R.string.dashboard_da_upload_title)
        }
        val type = targetCourseType.trim()
        return if (type.isBlank()) title else "$title - $type"
    }

    private fun checkSessionState(onResolved: (String) -> Unit) {
        webView.evaluateJavascript(SESSION_STATE_SCRIPT) { rawResult ->
            if (parseJsResult(rawResult) == SESSION_PRELOGIN) {
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

    private fun parseJsResult(rawResult: String?, lowercase: Boolean = true): String {
        val parsed = rawResult
            ?.trim()
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.replace("\\\\", "\\")
            ?.replace("\\\"", "\"")
            .orEmpty()
        return if (lowercase) parsed.lowercase() else parsed
    }

    private fun escapeForJs(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
    }

    private fun performAssessmentAction(
        item: DaAssessmentEntry,
        action: String,
        allowNativeDownload: Boolean = true,
    ) {
        val script = buildAssessmentActionScript(item, action, allowNativeDownload)
        webView.evaluateJavascript(script) { rawResult ->
            val parsedResult = parseJsResult(rawResult, lowercase = false)
            val payload = runCatching { JSONObject(parsedResult) }.getOrNull()
            if (
                payload != null &&
                payload.optString("status").equals("native_download", ignoreCase = true) &&
                allowNativeDownload
            ) {
                val request = parseNativeDownloadRequest(payload)
                if (request == null) {
                    performAssessmentAction(item, action, allowNativeDownload = false)
                } else {
                    executeNativeDownload(request, action) { success ->
                        if (!success) {
                            performAssessmentAction(item, action, allowNativeDownload = false)
                        }
                    }
                }
                return@evaluateJavascript
            }

            when (parsedResult.lowercase()) {
                "submitted",
                "done",
                "clicked",
                "changed",
                -> Unit

                JS_STATE_PRELOGIN -> redirectToLogin()
                else -> showAssessmentActionFailure(action)
            }
        }
    }

    private fun showAssessmentActionFailure(action: String) {
        val messageResId = when (action) {
            ACTION_DOWNLOAD_QUESTION -> R.string.da_download_qp_failed
            ACTION_DOWNLOAD_SUBMISSION -> R.string.da_download_da_failed
            else -> R.string.da_upload_da_failed
        }
        Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show()
    }

    private fun parseNativeDownloadRequest(payload: JSONObject): NativeDownloadRequest? {
        val url = payload.optString("url").trim()
        if (url.isBlank()) {
            return null
        }
        val method = payload.optString("method").trim().uppercase()
            .ifBlank { "POST" }
            .let { if (it == "GET") "GET" else "POST" }
        val referer = payload.optString("referer").trim()
        val fileName = payload.optString("fileName").trim()
        val fieldsArray = payload.optJSONArray("fields")
        val fields = buildList {
            if (fieldsArray != null) {
                for (index in 0 until fieldsArray.length()) {
                    val entry = fieldsArray.optJSONObject(index) ?: continue
                    val name = entry.optString("name").trim()
                    if (name.isBlank()) {
                        continue
                    }
                    add(
                        NativeDownloadField(
                            name = name,
                            value = entry.optString("value"),
                        ),
                    )
                }
            }
        }
        return NativeDownloadRequest(
            url = url,
            method = method,
            referer = referer,
            fileName = fileName,
            fields = fields,
        )
    }

    private fun executeNativeDownload(
        request: NativeDownloadRequest,
        action: String,
        onResult: (Boolean) -> Unit,
    ) {
        val userAgent = webView.settings.userAgentString
        val fallbackReferer = webView.url.orEmpty()
        Thread {
            val success = performNativeDownload(
                request = request,
                userAgent = userAgent,
                fallbackReferer = fallbackReferer,
                action = action,
            )
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
                }
                onResult(success)
            }
        }.start()
    }

    private fun performNativeDownload(
        request: NativeDownloadRequest,
        userAgent: String,
        fallbackReferer: String,
        action: String,
    ): Boolean {
        val method = request.method.uppercase().ifBlank { "POST" }
        val encodedBody = encodeFormBody(request.fields)
        val requestUrl = if (method == "GET" && encodedBody.isNotBlank()) {
            appendQuery(request.url, encodedBody)
        } else {
            request.url
        }

        val connection = (runCatching { URL(requestUrl).openConnection() }.getOrNull() as? HttpURLConnection)
            ?: return false

        val result = runCatching {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "*/*")

            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(requestUrl)
                ?: cookieManager.getCookie(request.url)
            if (!cookie.isNullOrBlank()) {
                connection.setRequestProperty("Cookie", cookie)
            }

            val referer = request.referer.ifBlank { fallbackReferer }
            if (referer.isNotBlank()) {
                connection.setRequestProperty("Referer", referer)
            }

            connection.requestMethod = method
            if (method == "POST") {
                val bodyBytes = encodedBody.toByteArray(StandardCharsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8",
                )
                connection.setRequestProperty("Content-Length", bodyBytes.size.toString())
                connection.outputStream.use { output ->
                    output.write(bodyBytes)
                }
            }

            val statusCode = connection.responseCode
            val responseBytes = (if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.use { input ->
                input.readBytes()
            } ?: ByteArray(0)

            if (statusCode !in 200..299 || responseBytes.isEmpty()) {
                false
            } else {
                val mimeType = connection.contentType.orEmpty()
                val contentDisposition = connection.getHeaderField("Content-Disposition")
                if (isHtmlPayload(mimeType, contentDisposition, responseBytes)) {
                    false
                } else {
                    val fileName = resolveDownloadFileName(
                        request = request,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType,
                        action = action,
                    )
                    persistDownloadedBytes(responseBytes, fileName, mimeType)
                }
            }
        }.getOrDefault(false)

        connection.disconnect()
        return result
    }

    private fun encodeFormBody(fields: List<NativeDownloadField>): String {
        if (fields.isEmpty()) {
            return ""
        }
        return fields.joinToString("&") { field ->
            "${URLEncoder.encode(field.name, StandardCharsets.UTF_8.name())}=${
                URLEncoder.encode(field.value, StandardCharsets.UTF_8.name())
            }"
        }
    }

    private fun appendQuery(url: String, query: String): String {
        if (query.isBlank()) {
            return url
        }
        val separator = if (url.contains("?")) "&" else "?"
        return "$url$separator$query"
    }

    private fun resolveDownloadFileName(
        request: NativeDownloadRequest,
        contentDisposition: String?,
        mimeType: String,
        action: String,
    ): String {
        val guessed = sanitizeFileName(URLUtil.guessFileName(request.url, contentDisposition, mimeType))
        val preferred = sanitizeFileName(request.fileName)
        if (preferred.isBlank()) {
            return guessed.ifBlank {
                val prefix = if (action == ACTION_DOWNLOAD_QUESTION) "QP" else "DA"
                "${prefix}_${System.currentTimeMillis()}"
            }
        }
        if (preferred.contains('.')) {
            return preferred
        }
        val extension = guessed.substringAfterLast('.', "")
        return if (extension.isNotBlank()) {
            "$preferred.$extension"
        } else {
            preferred
        }
    }

    private fun isHtmlPayload(mimeType: String, contentDisposition: String?, bytes: ByteArray): Boolean {
        if (contentDisposition?.contains("attachment", ignoreCase = true) == true) {
            return false
        }
        val normalizedMime = mimeType.substringBefore(";").trim().lowercase()
        if (normalizedMime == "text/html" || normalizedMime == "application/xhtml+xml") {
            return true
        }
        if (normalizedMime.isBlank() || normalizedMime == "text/plain") {
            val preview = bytes
                .copyOfRange(0, minOf(bytes.size, 256))
                .toString(Charsets.UTF_8)
                .lowercase()
            if (preview.contains("<html") || preview.contains("<!doctype html")) {
                return true
            }
        }
        return false
    }

    private fun buildAssessmentActionScript(
        item: DaAssessmentEntry,
        action: String,
        allowNativeDownload: Boolean,
    ): String {
        val actionLiteral = escapeForJs(action)
        val codeLiteral = escapeForJs(item.code.trim())
        val classIdLiteral = escapeForJs(item.classId.trim().ifBlank { targetClassId.trim() })
        val allowNativeDownloadLiteral = if (allowNativeDownload) "true" else "false"
        return """
            (function () {
              const action = '$actionLiteral';
              const targetCodeRaw = '$codeLiteral'.trim();
              const targetClassIdRaw = '$classIdLiteral'.trim();
              const allowNativeDownload = $allowNativeDownloadLiteral;
              const trimValue = (value) => (value || '').toString().trim();
              const normalize = (value) => trimValue(value).toLowerCase();
              const targetCode = normalize(targetCodeRaw);
              const targetClassId = normalize(targetClassIdRaw);
              const hasPreloginState = (doc) => {
                const path = normalize((doc.location && doc.location.pathname) || window.location.pathname || '');
                const hasRoleForms = !!doc.querySelector('#stdForm,#empForm,#parentForm,#alumniForm,form[action*="/prelogin/setup"]');
                const hasCredentialForm = !!doc.querySelector('#vtopLoginForm,input[type="password"],form[action*="/login"],form[action*="/doLogin"]');
                return hasRoleForms || hasCredentialForm || path.includes('/vtop/login') || path.includes('/vtop/prelogin') || path.includes('/vtop/initialprocess');
              };
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

              const docs = getDocuments();
              if (docs.some(doc => hasPreloginState(doc))) {
                return 'prelogin';
              }

              let formDoc = null;
              let formNode = null;
              docs.some(doc => {
                const candidate = doc.querySelector('form#daUpload');
                if (!candidate) {
                  return false;
                }
                formDoc = doc;
                formNode = candidate;
                return true;
              });

              if (!formNode || !formDoc) {
                return 'not_found';
              }

              const matches = (node) => {
                const code = normalize(node.getAttribute('data-code') || node.getAttribute('data-editcode') || '');
                const classId = normalize(node.getAttribute('data-classid') || node.getAttribute('data-editclassid') || '');
                const codeMatches = !targetCode || (code && code === targetCode);
                const classMatches = !targetClassId || (classId && classId === targetClassId);
                return codeMatches && (classMatches || !classId);
              };

              const resolveBestNode = (selector) => {
                const nodes = Array.from(formNode.querySelectorAll(selector));
                if (nodes.length === 0) {
                  return null;
                }
                return nodes.find(matches) || nodes[0] || null;
              };

              const clickNode = (node) => {
                if (!node || typeof node.click !== 'function') {
                  return false;
                }
                node.click();
                return true;
              };

              const setFieldValue = (form, keys, value) => {
                if (!form || !keys || keys.length === 0) {
                  return;
                }
                const normalizedKeys = keys.map(key => normalize(key));
                let field = Array.from(form.querySelectorAll('input,textarea,select')).find(input => {
                  const name = normalize(input.getAttribute('name') || '');
                  const id = normalize(input.getAttribute('id') || '');
                  return normalizedKeys.some(key => (
                    name === key ||
                    id === key ||
                    name.endsWith(key) ||
                    id.endsWith(key)
                  ));
                }) || null;
                if (!field) {
                  field = form.ownerDocument.createElement('input');
                  field.type = 'hidden';
                  field.name = keys[0];
                  form.appendChild(field);
                }
                field.value = value || '';
              };

              const collectFormFields = (form) => {
                const values = [];
                Array.from((form && form.elements) || []).forEach(element => {
                  const name = trimValue((element && element.name) || element.getAttribute('name') || '');
                  if (!name) {
                    return;
                  }
                  const type = normalize((element && element.type) || '');
                  if ((type === 'checkbox' || type === 'radio') && !element.checked) {
                    return;
                  }
                  if (element.tagName && normalize(element.tagName) === 'select' && element.multiple) {
                    Array.from(element.options || [])
                      .filter(option => option.selected)
                      .forEach(option => values.push({ name: name, value: trimValue(option.value) }));
                    return;
                  }
                  values.push({ name: name, value: trimValue(element.value) });
                });
                return values;
              };

              const upsertField = (fields, keys, value) => {
                if (!keys || keys.length === 0) {
                  return fields;
                }
                const normalizedKeys = keys.map(key => normalize(key));
                let replaced = false;
                const nextFields = fields.map(field => {
                  const fieldName = normalize(field.name || '');
                  const matchesKey = normalizedKeys.some(key => (
                    fieldName === key || fieldName.endsWith(key)
                  ));
                  if (!matchesKey) {
                    return field;
                  }
                  replaced = true;
                  return { name: field.name, value: value || '' };
                });
                if (!replaced) {
                  nextFields.push({ name: keys[0], value: value || '' });
                }
                return nextFields;
              };

              const findDownloadForm = (preferredSelector, actionTokens) => {
                const normalizedTokens = (actionTokens || [])
                  .map(token => normalize(token))
                  .filter(Boolean);

                for (const doc of docs) {
                  if (preferredSelector) {
                    const bySelector = doc.querySelector(preferredSelector);
                    if (bySelector) {
                      return bySelector;
                    }
                  }
                  const forms = Array.from(doc.querySelectorAll('form'));
                  for (const form of forms) {
                    const actionValue = normalize(form.getAttribute('action') || form.action || '');
                    const formId = normalize(form.id || '');
                    const formName = normalize(form.getAttribute('name') || '');
                    const tokenMatch = normalizedTokens.some(token => (
                      actionValue.includes(token) || formId.includes(token) || formName.includes(token)
                    ));
                    if (tokenMatch) {
                      return form;
                    }
                  }
                }
                return null;
              };

              const buildNativeDownloadPayload = (form, triggerNode, codeVal, classIdVal, codeFields, classIdFields, isSubmission) => {
                if (!form) {
                  return null;
                }
                setFieldValue(form, codeFields, codeVal);
                setFieldValue(form, classIdFields, classIdVal);

                const actionUrlRaw = trimValue(form.getAttribute('action') || form.action || '');
                if (!actionUrlRaw) {
                  return null;
                }
                const actionUrl = (() => {
                  try {
                    return new URL(actionUrlRaw, (form.ownerDocument && form.ownerDocument.baseURI) || window.location.href).toString();
                  } catch (_) {
                    return actionUrlRaw;
                  }
                })();
                if (!actionUrl) {
                  return null;
                }

                let fields = collectFormFields(form);
                fields = upsertField(fields, codeFields, codeVal);
                fields = upsertField(fields, classIdFields, classIdVal);
                const triggerName = trimValue((triggerNode && triggerNode.getAttribute('name')) || '');
                if (triggerName) {
                  const triggerValue = trimValue(
                    (triggerNode && triggerNode.getAttribute('value')) ||
                    (triggerNode && triggerNode.value) ||
                    'true'
                  );
                  fields = upsertField(fields, [triggerName], triggerValue);
                }

                const sanitizedCode = trimValue(codeVal || 'assignment').replace(/[\\/:*?"<>|]+/g, '_');
                return {
                  status: 'native_download',
                  method: trimValue(form.method || 'POST').toUpperCase() || 'POST',
                  url: actionUrl,
                  referer: ((form.ownerDocument && form.ownerDocument.location && form.ownerDocument.location.href) || window.location.href || ''),
                  fileName: (isSubmission ? 'DA_' : 'QP_') + sanitizedCode,
                  fields: fields
                };
              };

              if (action === 'download_question') {
                const button = resolveBestNode('button[name="downloadQuestion"],button#downloadQuestion');
                if (!button) {
                  return 'not_found';
                }
                const code = trimValue(button.getAttribute('data-code') || targetCodeRaw);
                const classId = trimValue(button.getAttribute('data-classid') || targetClassIdRaw);
                const qpCodeFields = ['code', 'code1'];
                const qpClassIdFields = ['classidnumber', 'classid', 'classid1', 'classidnumber1'];
                const qpForm = findDownloadForm(
                  '#downloadQuestionForm,form[action*="downloadQuestion"],form[action*="downloadquestion"]',
                  ['dodownloadquestion', 'downloadquestion', 'downloadquestionform', 'downloadquestionpaper']
                );
                if (qpForm) {
                  setFieldValue(qpForm, qpCodeFields, code);
                  setFieldValue(qpForm, qpClassIdFields, classId);
                }
                if (allowNativeDownload && qpForm) {
                  const payload = buildNativeDownloadPayload(
                    qpForm,
                    button,
                    code,
                    classId,
                    qpCodeFields,
                    qpClassIdFields,
                    false
                  );
                  if (payload) {
                    return JSON.stringify(payload);
                  }
                }
                if (clickNode(button)) {
                  return 'clicked';
                }
                if (qpForm) {
                  const host = qpForm.ownerDocument.defaultView || window;
                  if (host && typeof host.doDownloadQuestion === 'function') {
                    host.doDownloadQuestion(code, classId);
                    return 'submitted';
                  }
                  if (typeof qpForm.requestSubmit === 'function') {
                    qpForm.requestSubmit();
                    return 'submitted';
                  }
                  if (typeof qpForm.submit === 'function') {
                    qpForm.submit();
                    return 'submitted';
                  }
                }
                return 'not_found';
              }

              if (action === 'download_submission') {
                const button = resolveBestNode('button[name="downloadStudentDA"],button#downloadStudentDA');
                if (!button) {
                  return 'not_found';
                }
                const code = trimValue(button.getAttribute('data-code') || targetCodeRaw);
                const classId = trimValue(button.getAttribute('data-classid') || targetClassIdRaw);
                const daCodeFields = ['code1', 'code'];
                const daClassIdFields = ['classid1', 'classid', 'classidnumber', 'classidnumber1'];
                const daForm = findDownloadForm(
                  '#downloadStudentDAForm,form[action*="downloadStudentDA"],form[action*="downloadstudentda"]',
                  ['downloadstudentda', 'downloadstudentdaform', 'dodownloadstudentda', 'downloadsubmission', 'dodownloadsubmission']
                );
                if (daForm) {
                  setFieldValue(daForm, daCodeFields, code);
                  setFieldValue(daForm, daClassIdFields, classId);
                }
                if (allowNativeDownload && daForm) {
                  const payload = buildNativeDownloadPayload(
                    daForm,
                    button,
                    code,
                    classId,
                    daCodeFields,
                    daClassIdFields,
                    true
                  );
                  if (payload) {
                    return JSON.stringify(payload);
                  }
                }
                if (clickNode(button)) {
                  return 'clicked';
                }
                if (daForm) {
                  const host = daForm.ownerDocument.defaultView || window;
                  if (host && typeof host.doDownloadStudentDA === 'function') {
                    host.doDownloadStudentDA(code, classId);
                    return 'submitted';
                  }
                  if (typeof daForm.requestSubmit === 'function') {
                    daForm.requestSubmit();
                    return 'submitted';
                  }
                  if (typeof daForm.submit === 'function') {
                    daForm.submit();
                    return 'submitted';
                  }
                }
                return 'not_found';
              }

              if (action === 'upload_edit') {
                const editButton = resolveBestNode('button[name="editAssignment"],button#editAssignment,[onclick*="doDAssignmentProcess"]');
                if (clickNode(editButton)) {
                  return 'submitted';
                }
                const host = formDoc.defaultView || window;
                if (host && typeof host.doDAssignmentProcess === 'function') {
                  host.doDAssignmentProcess();
                  return 'submitted';
                }
                return 'not_found';
              }

              return 'not_found';
            })();
        """.trimIndent()
    }

    private fun buildBlobDownloadScript(blobUrl: String, fileName: String, mimeType: String): String {
        val blobUrlLiteral = escapeForJs(blobUrl)
        val fileNameLiteral = escapeForJs(fileName)
        val mimeTypeLiteral = escapeForJs(mimeType)
        return """
            (function () {
              const blobUrl = '$blobUrlLiteral';
              const fileName = '$fileNameLiteral';
              const mimeType = '$mimeTypeLiteral';
              fetch(blobUrl)
                .then(response => response.blob())
                .then(blob => {
                  const reader = new FileReader();
                  reader.onloadend = function () {
                    const result = reader.result || '';
                    const commaIndex = result.indexOf(',');
                    const base64Data = commaIndex >= 0 ? result.substring(commaIndex + 1) : '';
                    const resolvedType = blob.type || mimeType || 'application/octet-stream';
                    if (
                      window.$BLOB_BRIDGE_NAME &&
                      typeof window.$BLOB_BRIDGE_NAME.onBlobDownload === 'function'
                    ) {
                      window.$BLOB_BRIDGE_NAME.onBlobDownload(base64Data, resolvedType, fileName);
                    }
                  };
                  reader.readAsDataURL(blob);
                })
                .catch(() => {
                  if (
                    window.$BLOB_BRIDGE_NAME &&
                    typeof window.$BLOB_BRIDGE_NAME.onBlobError === 'function'
                  ) {
                    window.$BLOB_BRIDGE_NAME.onBlobError();
                  }
                });
            })();
        """.trimIndent()
    }

    private fun persistDownloadedBytes(bytes: ByteArray, fileName: String, mimeType: String): Boolean {
        val safeName = sanitizeFileName(fileName.ifBlank { "da_${System.currentTimeMillis()}" })
        val resolvedMimeType = mimeType.ifBlank { "application/octet-stream" }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, resolvedMimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { output ->
                    output.write(bytes)
                } ?: return false
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val targetFile = File(downloadDir, safeName)
                FileOutputStream(targetFile).use { output ->
                    output.write(bytes)
                }
            }
            true
        }.getOrElse { false }
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private inner class BlobDownloadBridge {
        @JavascriptInterface
        fun onBlobDownload(base64Data: String?, mimeType: String?, fileName: String?) {
            if (base64Data.isNullOrBlank()) {
                runOnUiThread {
                    Toast.makeText(this@DaAssessmentsActivity, R.string.da_download_da_failed, Toast.LENGTH_SHORT).show()
                }
                return
            }
            val decoded = runCatching { Base64.decode(base64Data, Base64.DEFAULT) }.getOrNull()
            if (decoded == null || decoded.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this@DaAssessmentsActivity, R.string.da_download_da_failed, Toast.LENGTH_SHORT).show()
                }
                return
            }
            Thread {
                val success = persistDownloadedBytes(decoded, fileName.orEmpty(), mimeType.orEmpty())
                runOnUiThread {
                    val messageRes = if (success) R.string.download_started else R.string.da_download_da_failed
                    Toast.makeText(this@DaAssessmentsActivity, messageRes, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        @JavascriptInterface
        fun onBlobError() {
            runOnUiThread {
                Toast.makeText(this@DaAssessmentsActivity, R.string.da_download_da_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildOpenCourseScript(classId: String, semesterValue: String?): String {
        val classIdLiteral = escapeForJs(classId)
        val semesterValueLiteral = escapeForJs(semesterValue.orEmpty())
        return """
            (function () {
              const targetClassId = '$classIdLiteral';
              const semesterValue = '$semesterValueLiteral';
              const normalize = (value) => (value || '').toString().toLowerCase().trim();
              const hasPreloginState = (doc) => {
                const path = normalize((doc.location && doc.location.pathname) || window.location.pathname || '');
                const hasRoleForms = !!doc.querySelector('#stdForm,#empForm,#parentForm,#alumniForm,form[action*="/prelogin/setup"]');
                const hasCredentialForm = !!doc.querySelector('#vtopLoginForm,input[type="password"],form[action*="/login"],form[action*="/doLogin"]');
                return hasRoleForms || hasCredentialForm || path.includes('/vtop/login') || path.includes('/vtop/prelogin') || path.includes('/vtop/initialprocess');
              };
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
              const getCells = (row) => {
                return Array.from((row && row.children) || [])
                  .filter(cell => {
                    const tag = normalize(cell.tagName || '');
                    return tag === 'td' || tag === 'th';
                  })
                  .map(cell => normalize(cell.innerText || cell.textContent));
              };

              const docs = getDocuments();
              if (docs.some(doc => hasPreloginState(doc))) {
                return 'prelogin';
              }

              const targetLower = normalize(targetClassId);
              const alreadyOpen = docs.some(doc => {
                const form = doc.querySelector('form#daUpload');
                if (!form) {
                  return false;
                }
                const row = form.querySelector('tr.tableContent,tr.fixedContent.tableContent');
                if (!row) {
                  return false;
                }
                const cells = getCells(row);
                const classId = cells.length > 4 ? cells[4] : '';
                return classId && normalize(classId) === targetLower;
              });
              if (alreadyOpen) {
                return 'done';
              }

              let triggered = false;
              docs.forEach(doc => {
                if (triggered) {
                  return;
                }
                const form = doc.querySelector('form#digitalAssignment');
                if (!form) {
                  return;
                }

                const host = doc.defaultView || window;
                if (host && typeof host.myFunction === 'function') {
                  try {
                    host.myFunction(targetClassId);
                    triggered = true;
                    return;
                  } catch (_) {
                    // Try click-based fallback below.
                  }
                }

                const candidate = Array.from(form.querySelectorAll('[onclick]')).find(node => {
                  const onClick = normalize(node.getAttribute('onclick') || '');
                  return onClick.includes('myfunction') && onClick.includes(targetLower);
                });
                if (candidate && typeof candidate.click === 'function') {
                  candidate.click();
                  triggered = true;
                }
              });

              if (triggered) {
                return 'submitted';
              }

              if (semesterValue) {
                const reloadHosts = [window, window.parent];
                for (let i = 0; i < reloadHosts.length; i++) {
                  try {
                    const host = reloadHosts[i];
                    if (host && typeof host.reload === 'function') {
                      host.reload(semesterValue);
                      return 'reloaded';
                    }
                  } catch (_) {
                    // Ignore cross-origin errors.
                  }
                }
              }

              return 'not_found';
            })();
        """.trimIndent()
    }

    private fun toJsArrayLiteral(values: List<String>, lowercase: Boolean = true): String {
        return values
            .asSequence()
            .map { if (lowercase) it.lowercase() else it }
            .distinct()
            .joinToString(prefix = "[", postfix = "]") { "'${escapeForJs(it)}'" }
    }

    private fun buildDaNavigationScript(attemptNumber: Int): String {
        val routeTokensLiteral = toJsArrayLiteral(DA_ROUTE_TOKENS)
        val fallbackPathsLiteral = toJsArrayLiteral(DA_FALLBACK_PATHS, lowercase = false)
        val menuIdsLiteral = toJsArrayLiteral(DA_MENU_IDS)
        val textTermsLiteral = toJsArrayLiteral(DA_TEXT_TERMS)
        return """
            (function () {
              const routeTokens = $routeTokensLiteral;
              const fallbackPaths = $fallbackPathsLiteral;
              const menuIds = $menuIdsLiteral;
              const textTerms = $textTermsLiteral;
              const attemptNumber = $attemptNumber;
              const normalize = (value) => (value || '').toString().toLowerCase().trim();
              const normalizePath = (value) => normalize(value).replace(/^\/+/, '');
              const notFoundSignals = ['http status 404','404 not found','page not found','error 404','resource not found'];

              const hasPreloginState = (doc) => {
                const path = normalize((doc.location && doc.location.pathname) || window.location.pathname || '');
                const hasRoleForms = !!doc.querySelector('#stdForm,#empForm,#parentForm,#alumniForm,form[action*="/prelogin/setup"]');
                const hasCredentialForm = !!doc.querySelector('#vtopLoginForm,input[type="password"],form[action*="/login"],form[action*="/doLogin"]');
                return hasRoleForms || hasCredentialForm || path.includes('/vtop/login') || path.includes('/vtop/prelogin') || path.includes('/vtop/initialprocess');
              };

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

              const docs = getDocuments();
              if (docs.some(doc => hasPreloginState(doc))) {
                return 'prelogin';
              }

              const hasNotFoundContent = (doc) => {
                const bodyText = normalize((doc.body && (doc.body.innerText || doc.body.textContent)) || '');
                return bodyText && notFoundSignals.some(signal => bodyText.includes(signal));
              };
              if (docs.some(doc => hasNotFoundContent(doc))) {
                return 'not_found_page';
              }

              const hasDaForms = docs.some(doc => !!doc.querySelector('form#digitalAssignment,form#daUpload'));
              const currentPath = normalizePath(window.location.pathname + window.location.search);
              if (hasDaForms || routeTokens.some(token => currentPath.includes(token))) {
                return 'done';
              }

              const hasMenuId = (value) => {
                const normalized = normalize(value);
                return normalized && menuIds.some(menuId => normalized === menuId || normalized.endsWith(menuId));
              };
              const hasRouteToken = (value) => {
                const normalized = normalizePath(value);
                return normalized && routeTokens.some(token => normalized.includes(token));
              };
              const hasTextTerm = (value) => {
                const normalized = normalize(value);
                return normalized && textTerms.some(term => normalized.includes(term));
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
                  existing.score = Math.max(existing.score, score);
                  return;
                }
                candidates.push({ node: clickable, score });
              };

              docs.forEach(doc => {
                Array.from(doc.querySelectorAll('[id]')).forEach(node => {
                  if (hasMenuId(node.getAttribute('id'))) {
                    addCandidate(node, 20);
                  }
                });
                Array.from(doc.querySelectorAll('[data-url],a[href],button,[role="button"],[onclick],li,div,span')).forEach(node => {
                  const id = node.getAttribute('id');
                  const dataUrl = node.getAttribute('data-url');
                  const href = node.getAttribute('href');
                  const onClick = node.getAttribute('onclick');
                  const text = node.innerText || node.textContent;
                  let score = 0;
                  if (hasMenuId(id)) score += 12;
                  if (hasRouteToken(dataUrl) || hasRouteToken(href) || hasRouteToken(onClick)) score += 12;
                  if (hasTextTerm(text)) score += 8;
                  if (normalize(onClick).includes('loadmydiv(')) score += 5;
                  if (score > 0) {
                    addCandidate(node, score);
                  }
                });
              });

              if (candidates.length > 0) {
                candidates.sort((a, b) => b.score - a.score);
                candidates[0].node.click();
                return 'clicked';
              }

              const fallbackOffset = fallbackPaths.length > 0 ? (Math.max(attemptNumber, 1) - 1) % fallbackPaths.length : 0;
              const orderedFallbacks = fallbackPaths.slice(fallbackOffset).concat(fallbackPaths.slice(0, fallbackOffset));
              const fallbackPath = orderedFallbacks.find(path => {
                const normalizedPath = normalizePath(path);
                return normalizedPath && !currentPath.includes(normalizedPath);
              });

              const loadMyDivHost = (() => {
                if (typeof window.loadmydiv === 'function') return window;
                if (window.parent && typeof window.parent.loadmydiv === 'function') return window.parent;
                return null;
              })();
              if (loadMyDivHost && fallbackPath) {
                try {
                  loadMyDivHost.loadmydiv(fallbackPath.replace(/^\/+/, ''));
                  return 'clicked';
                } catch (_) {
                  // Try direct path below.
                }
              }

              if (fallbackPath) {
                window.location.assign('/vtop/' + fallbackPath.replace(/^\/+/, ''));
                return 'navigated';
              }

              return 'not_found';
            })();
        """.trimIndent()
    }

    private inner class DaAssessmentsAdapter : RecyclerView.Adapter<DaAssessmentViewHolder>() {
        private val items = mutableListOf<DaAssessmentEntry>()

        fun submit(newItems: List<DaAssessmentEntry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DaAssessmentViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_da_assessment, parent, false)
            return DaAssessmentViewHolder(view as ViewGroup)
        }

        override fun onBindViewHolder(holder: DaAssessmentViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class DaAssessmentViewHolder(root: ViewGroup) : RecyclerView.ViewHolder(root) {
        private val titleText: TextView = root.findViewById(R.id.assessmentTitleText)
        private val lastDateText: TextView = root.findViewById(R.id.assessmentLastDateText)
        private val statusText: TextView = root.findViewById(R.id.assessmentStatusText)
        private val actionsRow: ViewGroup = root.findViewById(R.id.assessmentActionsRow)
        private val downloadQuestionButton: MaterialButton = root.findViewById(R.id.downloadQuestionButton)
        private val downloadDaButton: MaterialButton = root.findViewById(R.id.downloadDaButton)
        private val uploadDaButton: MaterialButton = root.findViewById(R.id.uploadDaButton)

        fun bind(item: DaAssessmentEntry) {
            titleText.text = item.title.trim().ifBlank { getString(R.string.marks_value_na) }
            val formattedDate = normalizeDateForDisplay(item.lastDate)
                .ifBlank { getString(R.string.marks_value_dash) }
            lastDateText.text = getString(R.string.da_last_date_format, formattedDate)

            val status = item.status.trim().ifBlank { getString(R.string.da_due_over) }
            statusText.text = status
            val isOver = status.lowercase().contains("over")
            statusText.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (isOver) R.color.marks_grade_poor else R.color.marks_text_secondary,
                ),
            )

            val hasAnyActions =
                item.canDownloadQuestion || item.canDownloadSubmission || item.canUploadEdit
            actionsRow.isVisible = hasAnyActions

            downloadQuestionButton.isVisible = item.canDownloadQuestion
            downloadDaButton.isVisible = item.canDownloadSubmission
            uploadDaButton.isVisible = item.canUploadEdit

            downloadQuestionButton.setOnClickListener { performAssessmentAction(item, ACTION_DOWNLOAD_QUESTION) }
            downloadDaButton.setOnClickListener { performAssessmentAction(item, ACTION_DOWNLOAD_SUBMISSION) }
            uploadDaButton.setOnClickListener { performAssessmentAction(item, ACTION_UPLOAD_EDIT) }
        }
    }

    private fun normalizeDateForDisplay(value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            return ""
        }
        val match = DATE_WITH_ALPHA_MONTH_REGEX.find(normalized) ?: return normalized
        val day = match.groupValues[1]
        val month = match.groupValues[2].uppercase()
        val year = match.groupValues[3]
        val numericMonth = monthMap[month] ?: return normalized
        return "$day-$numericMonth-$year"
    }

    private data class DaAssessmentEntry(
        val title: String,
        val lastDate: String,
        val status: String,
        val code: String,
        val classId: String,
        val canDownloadQuestion: Boolean,
        val canDownloadSubmission: Boolean,
        val canUploadEdit: Boolean,
    )

    private data class NativeDownloadField(
        val name: String,
        val value: String,
    )

    private data class NativeDownloadRequest(
        val url: String,
        val method: String,
        val referer: String,
        val fileName: String,
        val fields: List<NativeDownloadField>,
    )

    companion object {
        const val EXTRA_CLASS_ID = "da_class_id"
        const val EXTRA_COURSE_CODE = "da_course_code"
        const val EXTRA_COURSE_TITLE = "da_course_title"
        const val EXTRA_COURSE_TYPE = "da_course_type"
        const val EXTRA_SEMESTER_VALUE = "da_semester_value"

        private const val HOME_URL = "https://vtop.vit.ac.in/vtop/content"

        private val DA_ROUTE_TOKENS = listOf(
            "examinations/studentda",
            "examinations/daupload",
            "examinations/digitalassignment",
            "examinations/processdigitalassignment",
            "academics/common/studentda",
        )
        private val DA_FALLBACK_PATHS = listOf(
            "examinations/StudentDA",
            "examinations/studentda",
            "examinations/processDigitalAssignment",
            "examinations/daupload",
            "academics/common/studentda",
        )
        private val DA_MENU_IDS = listOf("EXM0017")
        private val DA_TEXT_TERMS = listOf(
            "da upload",
            "digital assignment",
            "assignment upload",
            "digital assignment upload",
        )

        private const val JS_STATE_DONE = "done"
        private const val JS_STATE_PRELOGIN = "prelogin"
        private const val JS_STATE_NOT_FOUND_PAGE = "not_found_page"
        private const val BLOB_BRIDGE_NAME = "AndroidBlobDownload"
        private const val ACTION_DOWNLOAD_QUESTION = "download_question"
        private const val ACTION_DOWNLOAD_SUBMISSION = "download_submission"
        private const val ACTION_UPLOAD_EDIT = "upload_edit"

        private const val MAX_NAVIGATION_ATTEMPTS = 12
        private const val NAVIGATION_RETRY_DELAY_MS = 700L
        private const val MAX_OPEN_COURSE_ATTEMPTS = 6
        private const val OPEN_COURSE_RETRY_DELAY_MS = 800L
        private const val MAX_ASSESSMENT_FETCH_RETRIES = 12
        private const val ASSESSMENT_RETRY_DELAY_MS = 1100L
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 25_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000

        private val DATE_WITH_ALPHA_MONTH_REGEX = Regex("""(\d{2})-([A-Za-z]{3})-(\d{4})""")
        private val monthMap = mapOf(
            "JAN" to "01",
            "FEB" to "02",
            "MAR" to "03",
            "APR" to "04",
            "MAY" to "05",
            "JUN" to "06",
            "JUL" to "07",
            "AUG" to "08",
            "SEP" to "09",
            "OCT" to "10",
            "NOV" to "11",
            "DEC" to "12",
        )

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

        private const val EXTRACT_DA_ASSESSMENTS_SCRIPT =
            """
            (function () {
              const normalize = (value) => (value || '').toString().trim();
              const normalizeLower = (value) => normalize(value).toLowerCase();
              const hasPreloginState = (doc) => {
                const path = normalizeLower((doc.location && doc.location.pathname) || window.location.pathname || '');
                const hasRoleForms = !!doc.querySelector('#stdForm,#empForm,#parentForm,#alumniForm,form[action*="/prelogin/setup"]');
                const hasCredentialForm = !!doc.querySelector('#vtopLoginForm,input[type="password"],form[action*="/login"],form[action*="/doLogin"]');
                return hasRoleForms || hasCredentialForm || path.includes('/vtop/login') || path.includes('/vtop/prelogin') || path.includes('/vtop/initialprocess');
              };
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
              const getCells = (row) => {
                return Array.from((row && row.children) || [])
                  .filter(cell => {
                    const tag = normalizeLower(cell.tagName || '');
                    return tag === 'td' || tag === 'th';
                  })
                  .map(cell => normalize(cell.innerText || cell.textContent));
              };
              const extractDate = (value) => {
                const text = normalize(value);
                const match = text.match(/(\d{2}-(?:[A-Za-z]{3}|\d{2})-\d{4})/);
                return match && match[1] ? match[1] : text;
              };

              const docs = getDocuments();
              if (docs.some(doc => hasPreloginState(doc))) {
                return JSON.stringify({ status: 'prelogin' });
              }

              let payload = null;
              docs.some(doc => {
                const form = doc.querySelector('form#daUpload');
                if (!form) {
                  return false;
                }

                const tables = Array.from(form.querySelectorAll('#fixedTableContainer table.customTable, table.customTable'));
                let infoCells = null;
                let assessmentTable = null;

                tables.forEach(table => {
                  const headerRows = Array.from(table.querySelectorAll('tr.tableHeader,tr.fixedContent.tableHeader'));
                  const headerText = headerRows
                    .map(row => getCells(row).join(' '))
                    .join(' ')
                    .toLowerCase();

                  if (!infoCells && headerText.includes('course code') && headerText.includes('course title')) {
                    const infoRow = table.querySelector('tr.tableContent,tr.fixedContent.tableContent');
                    if (infoRow) {
                      infoCells = getCells(infoRow);
                    }
                    return;
                  }

                  if (!assessmentTable && headerText.includes('due date') && headerText.includes('title')) {
                    assessmentTable = table;
                  }
                });

                if (!assessmentTable) {
                  return false;
                }

                const classId = infoCells && infoCells.length > 4 ? normalize(infoCells[4]) : '';
                const courseTitle = infoCells && infoCells.length > 2 ? normalize(infoCells[2]) : '';
                const courseType = infoCells && infoCells.length > 3 ? normalize(infoCells[3]) : '';
                const entries = [];

                Array.from(assessmentTable.querySelectorAll('tr.tableContent,tr.fixedContent.tableContent')).forEach(row => {
                  const cells = getCells(row);
                  if (cells.length < 5) {
                    return;
                  }
                  const title = normalize(cells[1]);
                  if (!title) {
                    return;
                  }
                  const dueRaw = normalize(cells[4]);
                  const dueLower = normalizeLower(dueRaw);
                  let status = '';
                  if (dueLower.includes('day') && dueLower.includes('left')) {
                    status = dueRaw.replace(/\s+/g, ' ').trim();
                  } else if (dueLower.length > 0) {
                    status = 'Due Date is over';
                  }
                  const uploadColumn = cells.length > 7 ? normalize(cells[7]) : '';
                  if (!status && uploadColumn) {
                    status = uploadColumn.replace(/\s+/g, ' ').trim();
                  }

                  const questionButton = row.querySelector('button[name="downloadQuestion"],button#downloadQuestion');
                  const downloadButton = row.querySelector('button[name="downloadStudentDA"],button#downloadStudentDA');
                  const editButton = row.querySelector('button[name="editAssignment"],button#editAssignment,[onclick*="doDAssignmentProcess"]');

                  const resolvedCode = normalize(
                    (questionButton && questionButton.getAttribute('data-code')) ||
                    (downloadButton && downloadButton.getAttribute('data-code')) ||
                    (editButton && editButton.getAttribute('data-editcode')) ||
                    ''
                  );
                  const resolvedClassId = normalize(
                    (questionButton && questionButton.getAttribute('data-classid')) ||
                    (downloadButton && downloadButton.getAttribute('data-classid')) ||
                    (editButton && editButton.getAttribute('data-editclassid')) ||
                    classId
                  );

                  entries.push({
                    title: title,
                    lastDate: extractDate(dueRaw),
                    status: status,
                    code: resolvedCode,
                    classId: resolvedClassId,
                    canDownloadQuestion: !!questionButton,
                    canDownloadSubmission: !!downloadButton,
                    canUploadEdit: !!editButton
                  });
                });

                payload = {
                  status: 'ok',
                  classId: classId,
                  courseTitle: courseTitle,
                  courseType: courseType,
                  entries: entries
                };
                return true;
              });

              if (!payload) {
                return JSON.stringify({ status: 'not_found' });
              }

              return JSON.stringify(payload);
            })();
            """
    }
}
