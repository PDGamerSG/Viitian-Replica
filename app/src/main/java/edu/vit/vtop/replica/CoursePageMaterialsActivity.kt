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
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
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

class CoursePageMaterialsActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var teacherSpinner: Spinner
    private lateinit var teacherStatusText: TextView
    private lateinit var materialList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var teacherAdapter: ArrayAdapter<String>
    private lateinit var materialsAdapter: CourseMaterialsAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val teacherOptions = mutableListOf<TeacherOption>()
    private val allMaterials = mutableListOf<CourseMaterialEntry>()

    private var navigationAttempts = 0
    private var navigationInProgress = false
    private var subjectOpenAttempts = 0
    private var subjectOpenRequested = false
    private var materialsFetchInProgress = false
    private var materialsFetchRetries = 0
    private var loginRedirectHandled = false
    private var suppressTeacherSelection = false
    private var selectedTeacherKey: String? = null

    private lateinit var targetCourseId: String
    private lateinit var targetCourseCode: String
    private lateinit var targetCourseTitle: String
    private lateinit var targetCourseType: String
    private var targetSemesterValue: String? = null
    private var targetRawLabel: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_course_page_materials)
        setSupportActionBar(findViewById(R.id.topAppBar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        targetCourseId = intent.getStringExtra(EXTRA_COURSE_ID).orEmpty()
        targetCourseCode = intent.getStringExtra(EXTRA_COURSE_CODE).orEmpty()
        targetCourseTitle = intent.getStringExtra(EXTRA_COURSE_TITLE).orEmpty()
        targetCourseType = intent.getStringExtra(EXTRA_COURSE_TYPE).orEmpty()
        targetSemesterValue = intent.getStringExtra(EXTRA_SEMESTER_VALUE)?.ifBlank { null }
        targetRawLabel = intent.getStringExtra(EXTRA_RAW_LABEL)?.ifBlank { null }

        supportActionBar?.title = buildToolbarTitle()

        progressBar = findViewById(R.id.pageLoadProgress)
        swipeRefreshLayout = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)
        teacherSpinner = findViewById(R.id.teacherSpinner)
        teacherStatusText = findViewById(R.id.teacherStatusText)
        materialList = findViewById(R.id.materialList)
        emptyText = findViewById(R.id.emptyText)

        materialsAdapter = CourseMaterialsAdapter()
        materialList.layoutManager = LinearLayoutManager(this)
        materialList.adapter = materialsAdapter

        teacherAdapter = ArrayAdapter(
            this,
            R.layout.item_semester_spinner,
            mutableListOf<String>(),
        ).also { adapter ->
            adapter.setDropDownViewResource(R.layout.item_semester_spinner_dropdown)
        }
        teacherSpinner.adapter = teacherAdapter
        teacherSpinner.isEnabled = false
        teacherSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                if (suppressTeacherSelection) {
                    return
                }
                val option = teacherOptions.getOrNull(position) ?: return
                selectedTeacherKey = option.key
                renderFilteredMaterials()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        swipeRefreshLayout.setColorSchemeResources(R.color.marks_indicator)
        swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.marks_surface)
        swipeRefreshLayout.setOnRefreshListener {
            navigationAttempts = 0
            subjectOpenAttempts = 0
            subjectOpenRequested = false
            materialsFetchRetries = 0
            selectedTeacherKey = null
            allMaterials.clear()
            teacherOptions.clear()
            renderMaterials(emptyList())
            showTeacherStatus(R.string.course_page_teacher_loading)
            emptyText.text = getString(R.string.course_page_materials_loading)
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
                        this@CoursePageMaterialsActivity,
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

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.isVisible = newProgress in 1..99
                progressBar.progress = newProgress
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
            showTeacherStatus(R.string.course_page_teacher_loading)
            emptyText.text = getString(R.string.course_page_materials_loading)
            renderMaterials(emptyList())
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
        webView.stopLoading()
        webView.setDownloadListener(null)
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
            } else if (!subjectOpenRequested) {
                ensureCoursePageReady()
            } else {
                fetchMaterials()
            }
        }
    }

    private fun ensureCoursePageReady() {
        if (navigationInProgress) {
            return
        }
        if (navigationAttempts >= MAX_NAVIGATION_ATTEMPTS) {
            emptyText.text = getString(R.string.course_page_materials_unavailable)
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        navigationInProgress = true
        navigationAttempts += 1
        webView.evaluateJavascript(buildCoursePageNavigationScript(navigationAttempts)) { rawResult ->
            navigationInProgress = false
            when (parseJsResult(rawResult)) {
                JS_STATE_DONE -> openTargetSubject()
                JS_STATE_PRELOGIN -> redirectToLogin()
                JS_STATE_NOT_FOUND_PAGE -> recoverFromNotFoundPage()
                else -> {
                    mainHandler.postDelayed(
                        { ensureCoursePageReady() },
                        NAVIGATION_RETRY_DELAY_MS,
                    )
                }
            }
        }
    }

    private fun recoverFromNotFoundPage() {
        navigationInProgress = false
        if (navigationAttempts >= MAX_NAVIGATION_ATTEMPTS) {
            emptyText.text = getString(R.string.course_page_materials_unavailable)
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        mainHandler.postDelayed(
            { webView.loadUrl(HOME_URL) },
            NAVIGATION_RETRY_DELAY_MS,
        )
    }

    private fun openTargetSubject() {
        if (targetCourseId.isBlank()) {
            emptyText.text = getString(R.string.course_page_open_subject_failed)
            return
        }
        if (subjectOpenAttempts >= MAX_OPEN_SUBJECT_ATTEMPTS) {
            emptyText.text = getString(R.string.course_page_open_subject_failed)
            Toast.makeText(this, R.string.course_page_open_subject_failed, Toast.LENGTH_SHORT).show()
            return
        }
        subjectOpenAttempts += 1
        val script = buildOpenSubjectScript()
        webView.evaluateJavascript(script) { rawResult ->
            when (parseJsResult(rawResult)) {
                "done",
                "submitted",
                "clicked",
                "changed",
                -> {
                    subjectOpenRequested = true
                    materialsFetchRetries = 0
                    mainHandler.postDelayed(
                        { fetchMaterials() },
                        MATERIALS_RETRY_DELAY_MS,
                    )
                }

                JS_STATE_PRELOGIN -> redirectToLogin()
                else -> {
                    mainHandler.postDelayed(
                        { openTargetSubject() },
                        OPEN_SUBJECT_RETRY_DELAY_MS,
                    )
                }
            }
        }
    }

    private fun fetchMaterials() {
        if (materialsFetchInProgress) {
            return
        }
        materialsFetchInProgress = true
        webView.evaluateJavascript(EXTRACT_COURSE_MATERIALS_SCRIPT) { rawResult ->
            materialsFetchInProgress = false
            val parsed = parseJsResult(rawResult, lowercase = false)
            if (parsed.isBlank()) {
                if (scheduleMaterialsRetry()) {
                    return@evaluateJavascript
                }
                renderMaterials(emptyList())
                return@evaluateJavascript
            }

            val payload = runCatching { JSONObject(parsed) }.getOrNull()
            if (payload == null) {
                if (scheduleMaterialsRetry()) {
                    return@evaluateJavascript
                }
                renderMaterials(emptyList())
                return@evaluateJavascript
            }

            when (payload.optString("status")) {
                "ok" -> {
                    val payloadCourseId = payload.optString("courseId").trim()
                    val payloadCourseType = payload.optString("courseType").trim()
                    if (
                        payloadCourseId.isNotBlank() &&
                        targetCourseId.isNotBlank() &&
                        !payloadCourseId.equals(targetCourseId, ignoreCase = true) &&
                        scheduleMaterialsRetry()
                    ) {
                        return@evaluateJavascript
                    }
                    if (
                        payloadCourseType.isNotBlank() &&
                        targetCourseType.isNotBlank() &&
                        !payloadCourseType.equals(targetCourseType, ignoreCase = true) &&
                        scheduleMaterialsRetry()
                    ) {
                        return@evaluateJavascript
                    }

                    val payloadTitle = payload.optString("courseTitle").trim()
                    val payloadCode = payload.optString("courseCode").trim()
                    val payloadType = payload.optString("courseType").trim()
                    if (payloadTitle.isNotBlank()) {
                        targetCourseTitle = payloadTitle
                    }
                    if (payloadCode.isNotBlank()) {
                        targetCourseCode = payloadCode
                    }
                    if (payloadType.isNotBlank()) {
                        targetCourseType = payloadType
                    }
                    supportActionBar?.title = buildToolbarTitle()

                    val entriesArray = payload.optJSONArray("entries")
                    val entries = buildList {
                        if (entriesArray != null) {
                            for (index in 0 until entriesArray.length()) {
                                val item = entriesArray.optJSONObject(index) ?: continue
                                add(
                                    CourseMaterialEntry(
                                        title = item.optString("title"),
                                        uploadedBy = item.optString("uploadedBy"),
                                        uploadedDate = item.optString("uploadedDate"),
                                        fileId = item.optString("fileId"),
                                        linkUrl = item.optString("linkUrl"),
                                        fileName = item.optString("fileName"),
                                        courseDetail = item.optString("courseDetail"),
                                    ),
                                )
                            }
                        }
                    }

                    if (
                        entries.isEmpty() &&
                        !payload.optBoolean("hasMaterialTable", true) &&
                        scheduleMaterialsRetry()
                    ) {
                        return@evaluateJavascript
                    }

                    materialsFetchRetries = 0
                    allMaterials.clear()
                    allMaterials.addAll(entries)
                    updateTeacherOptions(entries)
                    renderFilteredMaterials()
                }

                "prelogin" -> redirectToLogin()
                else -> {
                    if (scheduleMaterialsRetry()) {
                        return@evaluateJavascript
                    }
                    renderMaterials(emptyList())
                }
            }
        }
    }

    private fun scheduleMaterialsRetry(): Boolean {
        if (materialsFetchRetries >= MAX_MATERIALS_FETCH_RETRIES) {
            return false
        }
        materialsFetchRetries += 1
        emptyText.text = getString(R.string.course_page_materials_loading)
        mainHandler.postDelayed(
            { fetchMaterials() },
            MATERIALS_RETRY_DELAY_MS,
        )
        return true
    }

    private fun updateTeacherOptions(entries: List<CourseMaterialEntry>) {
        val uniqueTeachers = linkedMapOf<String, String>()
        entries.forEach { entry ->
            val identity = buildTeacherIdentity(entry.uploadedBy)
            uniqueTeachers.putIfAbsent(identity.key, identity.displayLabel)
        }

        val options = buildList {
            if (entries.isNotEmpty()) {
                add(TeacherOption(label = getString(R.string.course_page_teacher_all), key = ""))
            }
            uniqueTeachers.forEach { (key, label) ->
                add(TeacherOption(label = label, key = key))
            }
        }

        teacherOptions.clear()
        teacherOptions.addAll(options)

        suppressTeacherSelection = true
        teacherAdapter.clear()
        teacherAdapter.addAll(options.map { it.label })
        teacherAdapter.notifyDataSetChanged()

        if (options.isEmpty()) {
            teacherSpinner.isEnabled = false
            showTeacherStatus(null)
            selectedTeacherKey = null
        } else {
            val preferredIndex = options.indexOfFirst { it.key == selectedTeacherKey }
                .let { if (it >= 0) it else 0 }
            teacherSpinner.setSelection(preferredIndex, false)
            teacherSpinner.isEnabled = true
            selectedTeacherKey = options.getOrNull(preferredIndex)?.key
            showTeacherStatus(null)
        }

        suppressTeacherSelection = false
    }

    private fun buildTeacherIdentity(value: String): TeacherIdentity {
        val cleaned = value.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isBlank()) {
            return TeacherIdentity(
                key = "unknown",
                displayLabel = getString(R.string.course_page_teacher_unknown),
            )
        }

        val idFirstMatch = Regex("""^(\d{3,})\s*-\s*(.+?)(?:\s*-\s*.+)?$""").find(cleaned)
        val idInParensMatch = Regex("""^(.+?)\s*\((\d{3,})\)\s*$""").find(cleaned)

        val rawName: String
        val facultyId: String?
        when {
            idFirstMatch != null -> {
                facultyId = idFirstMatch.groupValues[1].trim()
                rawName = idFirstMatch.groupValues[2].trim()
            }

            idInParensMatch != null -> {
                rawName = idInParensMatch.groupValues[1].trim()
                facultyId = idInParensMatch.groupValues[2].trim()
            }

            else -> {
                rawName = cleaned.substringBefore(" - ").trim().ifBlank { cleaned }
                facultyId = null
            }
        }

        val normalizedName = rawName.replace(Regex("\\s+"), " ").trim()
        val key = normalizedName
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .ifBlank { "unknown" }
        val displayLabel = when {
            normalizedName.isBlank() -> getString(R.string.course_page_teacher_unknown)
            facultyId.isNullOrBlank() -> normalizedName
            else -> "$normalizedName ($facultyId)"
        }
        return TeacherIdentity(
            key = key,
            displayLabel = displayLabel,
        )
    }

    private fun renderFilteredMaterials() {
        val selectedKey = selectedTeacherKey?.trim().orEmpty()
        val filtered = if (selectedKey.isBlank()) {
            allMaterials.toList()
        } else {
            allMaterials.filter { entry ->
                buildTeacherIdentity(entry.uploadedBy).key == selectedKey
            }
        }
        renderMaterials(filtered)
    }

    private fun renderMaterials(entries: List<CourseMaterialEntry>) {
        materialsAdapter.submit(entries)
        materialList.isVisible = entries.isNotEmpty()
        emptyText.isVisible = entries.isEmpty()
        emptyText.text = if (entries.isEmpty()) {
            getString(R.string.course_page_no_materials)
        } else {
            getString(R.string.course_page_materials_loading)
        }
    }

    private fun showTeacherStatus(messageResId: Int?) {
        if (messageResId == null) {
            teacherStatusText.isVisible = false
            return
        }
        teacherStatusText.isVisible = true
        teacherStatusText.text = getString(messageResId)
    }

    private fun openMaterial(item: CourseMaterialEntry) {
        val linkUrl = item.linkUrl.trim()
        if (linkUrl.isNotBlank()) {
            openExternal(Uri.parse(linkUrl))
            return
        }
        performMaterialDownload(item)
    }

    private fun performMaterialDownload(item: CourseMaterialEntry) {
        if (item.fileId.trim().isBlank()) {
            Toast.makeText(this, R.string.course_page_download_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val script = buildMaterialActionScript(item)
        webView.evaluateJavascript(script) { rawResult ->
            val parsedResult = parseJsResult(rawResult, lowercase = false)
            val payload = runCatching { JSONObject(parsedResult) }.getOrNull()
            if (
                payload != null &&
                payload.optString("status").equals("native_download", ignoreCase = true)
            ) {
                val request = parseNativeDownloadRequest(payload)
                if (request == null) {
                    Toast.makeText(this, R.string.course_page_download_failed, Toast.LENGTH_SHORT).show()
                } else {
                    executeNativeDownload(request)
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
                else -> Toast.makeText(this, R.string.course_page_download_failed, Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun executeNativeDownload(request: NativeDownloadRequest) {
        val userAgent = webView.settings.userAgentString
        val fallbackReferer = webView.url.orEmpty()
        Thread {
            val success = performNativeDownload(
                request = request,
                userAgent = userAgent,
                fallbackReferer = fallbackReferer,
            )
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.course_page_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun performNativeDownload(
        request: NativeDownloadRequest,
        userAgent: String,
        fallbackReferer: String,
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
    ): String {
        val guessed = sanitizeFileName(URLUtil.guessFileName(request.url, contentDisposition, mimeType))
        val preferred = sanitizeFileName(request.fileName)
        if (preferred.isBlank()) {
            return guessed.ifBlank { "CM_${System.currentTimeMillis()}" }
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

    private fun persistDownloadedBytes(bytes: ByteArray, fileName: String, mimeType: String): Boolean {
        val safeName = sanitizeFileName(fileName.ifBlank { "cm_${System.currentTimeMillis()}" })
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

    private fun buildToolbarTitle(): String {
        val title = targetCourseTitle.trim()
        val type = targetCourseType.trim()
        val code = targetCourseCode.trim()
        return when {
            title.isBlank() && targetRawLabel?.isNotBlank() == true -> targetRawLabel.orEmpty()
            title.isBlank() -> getString(R.string.dashboard_course_page_title)
            type.isBlank() && code.isBlank() -> title
            type.isBlank() -> "$title - $code"
            code.isBlank() -> "$title - $type"
            else -> "$title - $type - $code"
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.unable_to_open_link, Toast.LENGTH_SHORT).show()
        }
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

    private fun toJsArrayLiteral(values: List<String>, lowercase: Boolean = true): String {
        return values
            .asSequence()
            .map { if (lowercase) it.lowercase() else it }
            .distinct()
            .joinToString(prefix = "[", postfix = "]") { "'${escapeForJs(it)}'" }
    }

    private fun buildOpenSubjectScript(): String {
        val courseIdLiteral = escapeForJs(targetCourseId.trim())
        val courseTypeLiteral = escapeForJs(targetCourseType.trim())
        val semesterValueLiteral = escapeForJs(targetSemesterValue.orEmpty())
        val rawLabelLiteral = escapeForJs(targetRawLabel.orEmpty())
        return """
            (function () {
              const targetCourseId = '$courseIdLiteral';
              const targetCourseType = '$courseTypeLiteral';
              const targetSemesterValue = '$semesterValueLiteral';
              const targetRawLabel = '$rawLabelLiteral';
              const trimValue = (value) => (value || '').toString().trim();
              const normalize = (value) => trimValue(value).toLowerCase();
            
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
            
              const matchesOption = (option) => {
                if (!option) {
                  return false;
                }
                const optionValue = trimValue(option.value);
                const optionType = trimValue(option.getAttribute('data-crstype') || '');
                const optionSemester = trimValue(option.getAttribute('data-semestr') || '');
                const optionLabel = trimValue(option.textContent || '');
            
                const idMatches = !targetCourseId || optionValue === targetCourseId;
                const typeMatches = !targetCourseType || normalize(optionType) === normalize(targetCourseType);
                const semesterMatches = !targetSemesterValue || optionSemester === targetSemesterValue;
                const labelMatches = !targetRawLabel || normalize(optionLabel) === normalize(targetRawLabel);
            
                return idMatches && (typeMatches || labelMatches) && (semesterMatches || !optionSemester);
              };
            
              let targetSelect = null;
              let targetOption = null;
              docs.some(doc => {
                const select = doc.querySelector('form#coursePageView select#courseId,form#coursePageView select[name="courseId"],select#courseId,select[name="courseId"]');
                if (!select) {
                  return false;
                }
                const options = Array.from(select.options || []);
                const exact = options.find(option => matchesOption(option));
                const byId = options.find(option => trimValue(option.value) === targetCourseId);
                const fallback = options.find(option => targetRawLabel && normalize(option.textContent || '') === normalize(targetRawLabel));
                const picked = exact || byId || fallback || null;
                if (!picked) {
                  return false;
                }
                targetSelect = select;
                targetOption = picked;
                return true;
              });
            
              if (!targetSelect || !targetOption) {
                return 'option_not_found';
              }
            
              const materialTable = document.querySelector('#materialTable tbody tr');
              if (
                trimValue(targetSelect.value) === trimValue(targetOption.value) &&
                materialTable
              ) {
                return 'done';
              }
            
              targetSelect.value = targetOption.value;
              targetOption.selected = true;
              targetSelect.dispatchEvent(new Event('input', { bubbles: true }));
              targetSelect.dispatchEvent(new Event('change', { bubbles: true }));
            
              const host = targetSelect.ownerDocument.defaultView || window;
              if (host && typeof host.getCourseDetail === 'function') {
                host.getCourseDetail(targetSelect);
                return 'submitted';
              }
            
              const form = targetSelect.form || targetSelect.closest('form');
              if (form) {
                if (typeof form.requestSubmit === 'function') {
                  form.requestSubmit();
                } else if (typeof form.submit === 'function') {
                  form.submit();
                }
                return 'submitted';
              }
            
              return 'changed';
            })();
        """.trimIndent()
    }

    private fun buildMaterialActionScript(item: CourseMaterialEntry): String {
        val fileIdLiteral = escapeForJs(item.fileId.trim())
        val fileNameLiteral = escapeForJs(item.fileName.trim().ifBlank { item.title.trim() })
        return """
            (function () {
              const targetFileId = '$fileIdLiteral';
              const preferredName = '$fileNameLiteral';
              const trimValue = (value) => (value || '').toString().trim();
              const normalize = (value) => trimValue(value).toLowerCase();
            
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
                const updated = fields.map(field => {
                  const fieldName = normalize(field.name || '');
                  const shouldReplace = normalizedKeys.some(key => (
                    fieldName === key ||
                    fieldName.endsWith(key)
                  ));
                  if (shouldReplace) {
                    replaced = true;
                    return { name: field.name, value: value };
                  }
                  return field;
                });
                if (!replaced) {
                  updated.push({ name: keys[0], value: value });
                }
                return updated;
              };
            
              const docs = getDocuments();
              if (docs.some(doc => hasPreloginState(doc))) {
                return 'prelogin';
              }
            
              let triggerButton = null;
              docs.some(doc => {
                const buttons = Array.from(doc.querySelectorAll('button[name="downloadmat"],button#downloadmat,[data-fileid]'));
                const matched = buttons.find(button => trimValue(button.getAttribute('data-fileid') || '') === targetFileId);
                if (matched) {
                  triggerButton = matched;
                  return true;
                }
                return false;
              });
            
              let downloadForm = null;
              docs.some(doc => {
                const form = doc.querySelector('form#courseMaterialstudentfileDown');
                if (form) {
                  downloadForm = form;
                  return true;
                }
                return false;
              });
            
              if (downloadForm && targetFileId) {
                let actionUrl = trimValue(downloadForm.getAttribute('action') || '');
                if (!actionUrl) {
                  return 'not_found';
                }
                if (!/^https?:\/\//i.test(actionUrl)) {
                  const origin = (
                    (downloadForm.ownerDocument && downloadForm.ownerDocument.location && downloadForm.ownerDocument.location.origin) ||
                    window.location.origin ||
                    ''
                  );
                  actionUrl = origin + (actionUrl.startsWith('/') ? actionUrl : '/' + actionUrl);
                }
            
                let fields = collectFormFields(downloadForm);
                fields = upsertField(fields, ['fileId', 'fileid'], targetFileId);
            
                const triggerName = trimValue((triggerButton && triggerButton.getAttribute('name')) || '');
                if (triggerName) {
                  const triggerValue = trimValue(
                    (triggerButton && triggerButton.getAttribute('value')) ||
                    (triggerButton && triggerButton.value) ||
                    'true'
                  );
                  fields = upsertField(fields, [triggerName], triggerValue);
                }
            
                const suggestedName = trimValue(preferredName || ('material_' + targetFileId)).replace(/[\\/:*?"<>|]+/g, '_');
                return JSON.stringify({
                  status: 'native_download',
                  method: trimValue(downloadForm.method || 'POST').toUpperCase() || 'POST',
                  url: actionUrl,
                  referer: ((downloadForm.ownerDocument && downloadForm.ownerDocument.location && downloadForm.ownerDocument.location.href) || window.location.href || ''),
                  fileName: suggestedName,
                  fields: fields
                });
              }
            
              if (triggerButton && typeof triggerButton.click === 'function') {
                triggerButton.click();
                return 'clicked';
              }
            
              return 'not_found';
            })();
        """.trimIndent()
    }

    private fun buildCoursePageNavigationScript(attemptNumber: Int): String {
        val routeTokensLiteral = toJsArrayLiteral(COURSE_PAGE_ROUTE_TOKENS)
        val fallbackPathsLiteral = toJsArrayLiteral(COURSE_PAGE_FALLBACK_PATHS, lowercase = false)
        val menuIdsLiteral = toJsArrayLiteral(COURSE_PAGE_MENU_IDS)
        val textTermsLiteral = toJsArrayLiteral(COURSE_PAGE_TEXT_TERMS)
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
            
              const hasCourseForm = docs.some(doc => !!doc.querySelector('form#coursePageView,select#courseId,select[name="courseId"]'));
              const currentPath = normalizePath(window.location.pathname + window.location.search);
              if (hasCourseForm || routeTokens.some(token => currentPath.includes(token))) {
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
                candidates.push({ node: clickable, score: score });
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
                  if (hasTextTerm(text)) score += 9;
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

    private inner class CourseMaterialsAdapter : RecyclerView.Adapter<CourseMaterialViewHolder>() {
        private val items = mutableListOf<CourseMaterialEntry>()

        fun submit(newItems: List<CourseMaterialEntry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseMaterialViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_course_page_material, parent, false)
            return CourseMaterialViewHolder(view as ViewGroup)
        }

        override fun onBindViewHolder(holder: CourseMaterialViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class CourseMaterialViewHolder(root: ViewGroup) : RecyclerView.ViewHolder(root) {
        private val titleText: TextView = root.findViewById(R.id.materialTitleText)
        private val metaText: TextView = root.findViewById(R.id.materialMetaText)
        private val dateText: TextView = root.findViewById(R.id.materialDateText)
        private val actionButton: MaterialButton = root.findViewById(R.id.materialActionButton)

        fun bind(item: CourseMaterialEntry) {
            titleText.text = item.title.trim().ifBlank { getString(R.string.marks_value_na) }
            val teacher = buildTeacherIdentity(item.uploadedBy).displayLabel
            metaText.text = getString(R.string.course_page_uploaded_by, teacher)
            val uploadedDate = item.uploadedDate.trim().ifBlank { getString(R.string.marks_value_dash) }
            dateText.text = getString(R.string.course_page_uploaded_on, uploadedDate)
            actionButton.text = if (item.linkUrl.trim().isBlank()) {
                getString(R.string.course_page_action_download)
            } else {
                getString(R.string.course_page_action_open)
            }
            actionButton.setOnClickListener { openMaterial(item) }
            itemView.setOnClickListener { openMaterial(item) }
        }
    }

    private data class CourseMaterialEntry(
        val title: String,
        val uploadedBy: String,
        val uploadedDate: String,
        val fileId: String,
        val linkUrl: String,
        val fileName: String,
        val courseDetail: String,
    )

    private data class TeacherOption(
        val label: String,
        val key: String,
    )

    private data class TeacherIdentity(
        val key: String,
        val displayLabel: String,
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
        const val EXTRA_COURSE_ID = "course_page_course_id"
        const val EXTRA_COURSE_CODE = "course_page_course_code"
        const val EXTRA_COURSE_TITLE = "course_page_course_title"
        const val EXTRA_COURSE_TYPE = "course_page_course_type"
        const val EXTRA_SEMESTER_VALUE = "course_page_semester_value"
        const val EXTRA_RAW_LABEL = "course_page_raw_label"

        private const val HOME_URL = "https://vtop.vit.ac.in/vtop/content"

        private val COURSE_PAGE_ROUTE_TOKENS = listOf(
            "academics/common/coursepage",
            "academics/common/studentcoursepage",
            "academics/common/coursematerial",
            "academics/common/studentcoursematerial",
            "academics/coursepage",
        )
        private val COURSE_PAGE_FALLBACK_PATHS = listOf(
            "academics/common/CoursePage",
            "academics/common/coursePage",
            "academics/common/coursepage",
            "academics/common/StudentCoursePage",
            "academics/common/coursematerial",
            "academics/coursepage",
        )
        private val COURSE_PAGE_MENU_IDS = emptyList<String>()
        private val COURSE_PAGE_TEXT_TERMS = listOf(
            "course page",
            "course pages",
            "course material",
            "course materials",
        )

        private const val JS_STATE_DONE = "done"
        private const val JS_STATE_PRELOGIN = "prelogin"
        private const val JS_STATE_NOT_FOUND_PAGE = "not_found_page"

        private const val MAX_NAVIGATION_ATTEMPTS = 12
        private const val NAVIGATION_RETRY_DELAY_MS = 700L
        private const val MAX_OPEN_SUBJECT_ATTEMPTS = 8
        private const val OPEN_SUBJECT_RETRY_DELAY_MS = 800L
        private const val MAX_MATERIALS_FETCH_RETRIES = 12
        private const val MATERIALS_RETRY_DELAY_MS = 1000L
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 25_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000

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

        private const val EXTRACT_COURSE_MATERIALS_SCRIPT =
            """
            (function () {
              const trimValue = (value) => (value || '').toString().trim();
              const normalize = (value) => trimValue(value);
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
                return Array.from((row && row.children) || []).filter(cell => {
                  const tag = normalizeLower(cell.tagName || '');
                  return tag === 'td' || tag === 'th';
                });
              };
            
              const extractCourseBits = (label, optionValue, optionType) => {
                const cleaned = normalize(label);
                const parts = cleaned.split(/\s+-\s+/).map(part => normalize(part)).filter(Boolean);
                const valueParts = normalize(optionValue).split('_').map(part => normalize(part)).filter(Boolean);
                const courseCode = valueParts.length >= 2 ? valueParts[1] : (parts.length > 1 ? parts[1] : '');
                const courseType = normalize(optionType) || (parts.length > 3 ? parts[3] : '');
                let courseTitle = '';
                if (courseCode) {
                  const marker = ' - ' + courseCode + ' - ';
                  const markerIndex = cleaned.indexOf(marker);
                  if (markerIndex >= 0) {
                    const afterCode = cleaned.substring(markerIndex + marker.length);
                    if (courseType) {
                      const typeMarker = ' - ' + courseType + ' - ';
                      const typeIndex = afterCode.lastIndexOf(typeMarker);
                      if (typeIndex > 0) {
                        courseTitle = normalize(afterCode.substring(0, typeIndex));
                      } else {
                        const shortTypeIndex = afterCode.lastIndexOf(' - ' + courseType);
                        if (shortTypeIndex > 0) {
                          courseTitle = normalize(afterCode.substring(0, shortTypeIndex));
                        }
                      }
                    }
                    if (!courseTitle) {
                      const chunks = afterCode.split(/\s+-\s+/).map(part => normalize(part)).filter(Boolean);
                      courseTitle = chunks.length > 0 ? chunks[0] : '';
                    }
                  }
                }
                if (!courseTitle && parts.length > 2) {
                  courseTitle = parts[2];
                }
                return {
                  courseCode: courseCode,
                  courseTitle: courseTitle,
                  courseType: courseType
                };
              };
            
              const docs = getDocuments();
              if (docs.some(doc => hasPreloginState(doc))) {
                return JSON.stringify({ status: 'prelogin' });
              }
            
              let payload = null;
              docs.some(doc => {
                const form = doc.querySelector('form#coursePageView');
                if (!form) {
                  return false;
                }
            
                const select = form.querySelector('select#courseId,select[name="courseId"]');
                if (!select) {
                  return false;
                }
            
                const selectedValue = normalize(select.value || '');
                const options = Array.from(select.options || []);
                const selectedOption = options.find(option => normalize(option.value) === selectedValue) || options.find(option => option.selected) || null;
            
                const selectedLabel = normalize((selectedOption && selectedOption.textContent) || '');
                const selectedType = normalize((selectedOption && selectedOption.getAttribute('data-crstype')) || '');
                const selectedId = normalize((selectedOption && (selectedOption.getAttribute('data-courseid') || selectedOption.value)) || selectedValue);
                const bits = extractCourseBits(
                  selectedLabel,
                  (selectedOption && selectedOption.value) || selectedValue,
                  selectedType
                );
            
                const materialTable = form.querySelector('#materialTable') || doc.querySelector('#materialTable');
                let rows = [];
                if (materialTable) {
                  rows = Array.from(materialTable.querySelectorAll('tbody tr'));
                  const host = doc.defaultView || window;
                  const jq = host && host.jQuery;
                  if (
                    jq &&
                    jq.fn &&
                    jq.fn.dataTable &&
                    (
                      jq.fn.dataTable.isDataTable(materialTable) ||
                      jq.fn.dataTable.isDataTable('#materialTable')
                    )
                  ) {
                    try {
                      const tableApi = jq.fn.dataTable.isDataTable(materialTable)
                        ? jq(materialTable).DataTable()
                        : jq('#materialTable').DataTable();
                      const allRowNodes = tableApi.rows().nodes().toArray();
                      if (allRowNodes && allRowNodes.length > 0) {
                        rows = allRowNodes;
                      }
                    } catch (_) {
                      // Fallback to visible DOM rows.
                    }
                  }
                }
                const entries = [];
            
                rows.forEach(row => {
                  const cells = getCells(row);
                  if (cells.length < 5) {
                    return;
                  }
            
                  const courseDetailCell = cells[1];
                  const materialCell = cells[2];
                  const uploadedCell = cells[3];
                  const actionCell = cells[4];
            
                  const materialLines = normalize(materialCell.innerText || materialCell.textContent)
                    .split('\n')
                    .map(line => normalize(line))
                    .filter(Boolean);
                  const uploadedLines = normalize(uploadedCell.innerText || uploadedCell.textContent)
                    .split('\n')
                    .map(line => normalize(line))
                    .filter(Boolean);
            
                  const title = materialLines.length > 0 ? materialLines[0] : normalize(materialCell.innerText || materialCell.textContent);
                  const uploadedBy = uploadedLines.length > 0 ? uploadedLines[0] : '';
                  const uploadedDate = uploadedLines.find(line => /\d{2}[-/]\d{2}[-/]\d{4}/.test(line)) || '';
                  const courseDetail = normalize(courseDetailCell.innerText || courseDetailCell.textContent);
            
                  const downloadButton = actionCell.querySelector('button[name="downloadmat"],button#downloadmat,[data-fileid]');
                  const linkNode = actionCell.querySelector('a[href]');
            
                  const fileId = normalize((downloadButton && downloadButton.getAttribute('data-fileid')) || '');
                  const linkUrl = trimValue((linkNode && linkNode.getAttribute('href')) || '');
                  const fileName = title.replace(/[\\/:*?"<>|]+/g, '_');
            
                  if (!title) {
                    return;
                  }
            
                  entries.push({
                    title: title,
                    uploadedBy: uploadedBy,
                    uploadedDate: uploadedDate,
                    fileId: fileId,
                    linkUrl: linkUrl,
                    fileName: fileName,
                    courseDetail: courseDetail
                  });
                });
            
                payload = {
                  status: 'ok',
                  hasMaterialTable: !!materialTable,
                  courseId: selectedId,
                  courseCode: bits.courseCode || '',
                  courseTitle: bits.courseTitle || '',
                  courseType: bits.courseType || '',
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
