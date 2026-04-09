package edu.vit.vtop.replica

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
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
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.json.JSONObject
import kotlin.math.roundToInt

class AttendanceActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var semesterSpinner: Spinner
    private lateinit var semesterStatusText: TextView
    private lateinit var averageAttendanceText: TextView
    private lateinit var attendanceList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var semesterAdapter: ArrayAdapter<String>
    private lateinit var attendanceAdapter: AttendanceAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val semesterOptions = mutableListOf<SemesterOption>()

    private var navigationAttempts = 0
    private var navigationInProgress = false
    private var semesterFetchInProgress = false
    private var attendanceFetchInProgress = false
    private var semesterFetchRetryAttempts = 0
    private var attendanceFetchRetryAttempts = 0
    private var pendingSemesterValue: String? = null
    private var pendingBaselineSignature: String? = null
    private var lastRenderedAttendanceSignature: String = ""
    private var suppressSemesterSelection = false
    private var loginRedirectHandled = false
    private var selectedSemesterValue: String? = null
    private var lastAppliedSemesterValue: String? = null
    private var initialSemesterRefreshPending = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attendance)
        setSupportActionBar(findViewById(R.id.topAppBar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progressBar = findViewById(R.id.pageLoadProgress)
        swipeRefreshLayout = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)
        semesterSpinner = findViewById(R.id.semesterSpinner)
        semesterStatusText = findViewById(R.id.semesterStatusText)
        averageAttendanceText = findViewById(R.id.averageAttendanceText)
        attendanceList = findViewById(R.id.attendanceList)
        emptyText = findViewById(R.id.emptyText)

        attendanceAdapter = AttendanceAdapter()
        attendanceList.layoutManager = LinearLayoutManager(this)
        attendanceList.adapter = attendanceAdapter

        semesterAdapter = ArrayAdapter(
            this,
            R.layout.item_semester_spinner,
            mutableListOf<String>(),
        ).also { adapter ->
            adapter.setDropDownViewResource(R.layout.item_semester_spinner_dropdown)
        }
        semesterSpinner.adapter = semesterAdapter
        semesterSpinner.isEnabled = false
        semesterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                if (suppressSemesterSelection) {
                    return
                }
                val option = semesterOptions.getOrNull(position) ?: return
                if (option.value == lastAppliedSemesterValue) {
                    return
                }
                applySemesterSelection(option)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        swipeRefreshLayout.setColorSchemeResources(R.color.marks_indicator)
        swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.marks_surface)
        swipeRefreshLayout.setOnRefreshListener {
            navigationAttempts = 0
            semesterFetchRetryAttempts = 0
            attendanceFetchRetryAttempts = 0
            pendingSemesterValue = null
            pendingBaselineSignature = null
            initialSemesterRefreshPending = true
            showSemesterStatus(R.string.attendance_semester_loading)
            renderAttendance(
                entries = emptyList(),
                averageLabel = getString(R.string.attendance_average_default),
            )
            emptyText.text = getString(R.string.attendance_loading)
            webView.reload()
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
                        this@AttendanceActivity,
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

        if (savedInstanceState == null) {
            showSemesterStatus(R.string.attendance_semester_loading)
            renderAttendance(
                entries = emptyList(),
                averageLabel = getString(R.string.attendance_average_default),
            )
            emptyText.text = getString(R.string.attendance_loading)
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
                ensureAttendancePageReady()
            }
        }
    }

    private fun ensureAttendancePageReady() {
        if (navigationInProgress) {
            return
        }
        if (navigationAttempts >= MAX_NAVIGATION_ATTEMPTS) {
            emptyText.text = getString(R.string.attendance_no_data)
            showSemesterStatus(R.string.attendance_semester_unavailable)
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        navigationInProgress = true
        navigationAttempts += 1
        webView.evaluateJavascript(buildAttendanceNavigationScript(navigationAttempts)) { rawResult ->
            navigationInProgress = false
            when (parseJsResult(rawResult)) {
                JS_STATE_DONE -> fetchSemesterOptions()
                JS_STATE_PRELOGIN -> redirectToLogin()
                JS_STATE_NOT_FOUND_PAGE -> recoverFromNotFoundPage()
                else -> {
                    mainHandler.postDelayed(
                        { ensureAttendancePageReady() },
                        NAVIGATION_RETRY_DELAY_MS,
                    )
                }
            }
        }
    }

    private fun recoverFromNotFoundPage() {
        navigationInProgress = false
        if (navigationAttempts >= MAX_NAVIGATION_ATTEMPTS) {
            emptyText.text = getString(R.string.attendance_no_data)
            showSemesterStatus(R.string.attendance_semester_unavailable)
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        mainHandler.postDelayed(
            { webView.loadUrl(HOME_URL) },
            NAVIGATION_RETRY_DELAY_MS,
        )
    }

    private fun fetchSemesterOptions() {
        if (semesterFetchInProgress) {
            return
        }
        semesterFetchInProgress = true
        webView.evaluateJavascript(EXTRACT_ATTENDANCE_SEMESTERS_SCRIPT) { rawResult ->
            semesterFetchInProgress = false
            val parsed = parseJsResult(rawResult, lowercase = false)
            if (parsed.isBlank()) {
                if (scheduleSemesterFetchRetry()) {
                    return@evaluateJavascript
                }
                showSemesterStatus(R.string.attendance_semester_unavailable)
                semesterSpinner.isEnabled = false
                return@evaluateJavascript
            }

            val payload = runCatching { JSONObject(parsed) }.getOrNull()
            if (payload == null) {
                if (scheduleSemesterFetchRetry()) {
                    return@evaluateJavascript
                }
                showSemesterStatus(R.string.attendance_semester_unavailable)
                semesterSpinner.isEnabled = false
                return@evaluateJavascript
            }

            when (payload.optString("status")) {
                "ok" -> {
                    val optionsArray = payload.optJSONArray("options")
                    val options = buildList {
                        if (optionsArray != null) {
                            for (index in 0 until optionsArray.length()) {
                                val item = optionsArray.optJSONObject(index) ?: continue
                                val label = item.optString("label").trim()
                                val value = item.optString("value").trim()
                                if (label.isNotBlank() && value.isNotBlank()) {
                                    add(SemesterOption(label = label, value = value))
                                }
                            }
                        }
                    }
                    if (options.isEmpty()) {
                        if (scheduleSemesterFetchRetry()) {
                            return@evaluateJavascript
                        }
                        showSemesterStatus(R.string.attendance_semester_unavailable)
                        semesterSpinner.isEnabled = false
                        return@evaluateJavascript
                    }

                    semesterFetchRetryAttempts = 0
                    val selectedValue = payload.optString("selectedValue").trim().ifEmpty { null }
                    showSemesterOptions(options, selectedValue)
                    if (initialSemesterRefreshPending) {
                        initialSemesterRefreshPending = false
                        (semesterOptions.firstOrNull { it.value == selectedSemesterValue }
                            ?: semesterOptions.firstOrNull())
                            ?.let { initialOption ->
                                applySemesterSelection(initialOption, forceRefresh = true)
                                return@evaluateJavascript
                            }
                    }
                    fetchAttendanceEntries()
                }

                "prelogin" -> redirectToLogin()
                else -> {
                    if (scheduleSemesterFetchRetry(retryNavigation = true)) {
                        return@evaluateJavascript
                    }
                    showSemesterStatus(R.string.attendance_semester_unavailable)
                    semesterSpinner.isEnabled = false
                }
            }
        }
    }

    private fun showSemesterOptions(options: List<SemesterOption>, selectedValue: String?) {
        semesterOptions.clear()
        semesterOptions.addAll(options)

        suppressSemesterSelection = true
        semesterAdapter.clear()
        semesterAdapter.addAll(options.map { it.label })
        semesterAdapter.notifyDataSetChanged()

        val preferredValue = lastAppliedSemesterValue ?: selectedValue ?: options.firstOrNull()?.value
        val preferredIndex = options.indexOfFirst { it.value == preferredValue }.let { if (it >= 0) it else 0 }
        semesterSpinner.setSelection(preferredIndex, false)
        semesterSpinner.isEnabled = true
        suppressSemesterSelection = false

        selectedSemesterValue = options.getOrNull(preferredIndex)?.value
        lastAppliedSemesterValue = selectedSemesterValue
        attendanceFetchRetryAttempts = 0
        showSemesterStatus(null)
    }

    private fun syncSemesterSpinnerSelection(semesterValue: String?) {
        val value = semesterValue?.trim().orEmpty()
        if (value.isBlank()) {
            return
        }
        val targetIndex = semesterOptions.indexOfFirst { it.value.equals(value, ignoreCase = true) }
        if (targetIndex < 0 || semesterSpinner.selectedItemPosition == targetIndex) {
            return
        }
        suppressSemesterSelection = true
        semesterSpinner.setSelection(targetIndex, false)
        suppressSemesterSelection = false
    }

    private fun applySemesterSelection(option: SemesterOption, forceRefresh: Boolean = false) {
        val script = buildApplySemesterScript(option)
        webView.evaluateJavascript(script) { rawResult ->
            when (parseJsResult(rawResult)) {
                APPLY_STATE_CHANGED,
                APPLY_STATE_SUBMITTED,
                APPLY_STATE_DONE,
                "clicked",
                "navigated",
                -> {
                    val previousSemesterValue = lastAppliedSemesterValue
                    val requestedSemesterValue = option.value
                    val semesterChanged =
                        forceRefresh ||
                            (previousSemesterValue != null &&
                                previousSemesterValue != requestedSemesterValue)
                    pendingSemesterValue = if (semesterChanged) requestedSemesterValue else null
                    pendingBaselineSignature =
                        if (semesterChanged) lastRenderedAttendanceSignature else null
                    selectedSemesterValue = requestedSemesterValue
                    lastAppliedSemesterValue = requestedSemesterValue
                    semesterFetchRetryAttempts = 0
                    attendanceFetchRetryAttempts = 0
                    showSemesterStatus(R.string.attendance_semester_loading)
                    renderAttendance(
                        entries = emptyList(),
                        averageLabel = getString(R.string.attendance_average_default),
                    )
                    mainHandler.postDelayed({ fetchSemesterOptions() }, ATTENDANCE_FETCH_RETRY_DELAY_MS)
                }

                JS_STATE_PRELOGIN -> redirectToLogin()
                "select_not_found",
                "option_not_found",
                "not_found",
                "",
                -> {
                    // The attendance fragment can briefly re-render; retry fetch flow once before surfacing an error.
                    showSemesterStatus(R.string.attendance_semester_loading)
                    mainHandler.postDelayed(
                        { fetchSemesterOptions() },
                        SEMESTER_FETCH_RETRY_DELAY_MS,
                    )
                }

                else -> {
                    Toast.makeText(this, R.string.attendance_semester_apply_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun scheduleSemesterFetchRetry(retryNavigation: Boolean = false): Boolean {
        if (semesterFetchRetryAttempts >= MAX_SEMESTER_FETCH_RETRIES) {
            return false
        }
        semesterFetchRetryAttempts += 1
        mainHandler.postDelayed(
            {
                if (retryNavigation) {
                    ensureAttendancePageReady()
                } else {
                    fetchSemesterOptions()
                }
            },
            SEMESTER_FETCH_RETRY_DELAY_MS,
        )
        return true
    }

    private fun scheduleAttendanceFetchRetry(): Boolean {
        if (attendanceFetchRetryAttempts >= MAX_ATTENDANCE_FETCH_RETRIES) {
            return false
        }
        attendanceFetchRetryAttempts += 1
        showSemesterStatus(R.string.attendance_semester_loading)
        mainHandler.postDelayed({ fetchAttendanceEntries() }, ATTENDANCE_FETCH_RETRY_DELAY_MS)
        return true
    }

    private fun fetchAttendanceEntries() {
        if (attendanceFetchInProgress) {
            return
        }
        attendanceFetchInProgress = true
        val expectedSemesterValue = (pendingSemesterValue ?: selectedSemesterValue ?: lastAppliedSemesterValue).orEmpty()
        val seedExpectedSemesterScript =
            "window.__attendanceExpectedSemester='${escapeForJs(expectedSemesterValue)}';"
        webView.evaluateJavascript(seedExpectedSemesterScript) {
            webView.evaluateJavascript(EXTRACT_ATTENDANCE_ENTRIES_SCRIPT) { rawResult ->
                attendanceFetchInProgress = false
                val parsed = parseJsResult(rawResult, lowercase = false)
                if (parsed.isBlank()) {
                    if (scheduleAttendanceFetchRetry()) {
                        return@evaluateJavascript
                    }
                    pendingSemesterValue = null
                    pendingBaselineSignature = null
                    showSemesterStatus(null)
                    renderAttendance(emptyList(), getString(R.string.attendance_average_default))
                    return@evaluateJavascript
                }

                val payload = runCatching { JSONObject(parsed) }.getOrNull()
                if (payload == null) {
                    if (scheduleAttendanceFetchRetry()) {
                        return@evaluateJavascript
                    }
                    pendingSemesterValue = null
                    pendingBaselineSignature = null
                    showSemesterStatus(null)
                    renderAttendance(emptyList(), getString(R.string.attendance_average_default))
                    return@evaluateJavascript
                }

                when (payload.optString("status")) {
                    "ok" -> {
                        val payloadSemesterValue =
                            payload.optString("selectedSemesterValue").trim().ifEmpty { null }
                        val entriesArray = payload.optJSONArray("entries")
                        val entries = buildList {
                            if (entriesArray != null) {
                                for (index in 0 until entriesArray.length()) {
                                    val item = entriesArray.optJSONObject(index) ?: continue
                                    add(
                                        AttendanceEntry(
                                            subjectTitle = item.optString("subjectTitle"),
                                            courseDetail = item.optString("courseDetail"),
                                            courseCode = item.optString("courseCode"),
                                            courseType = item.optString("courseType"),
                                            classGroup = item.optString("classGroup"),
                                            classDetail = item.optString("classDetail"),
                                            facultyDetail = item.optString("facultyDetail"),
                                            attendedClassesText = item.optString("attendedClassesText"),
                                            totalClassesText = item.optString("totalClassesText"),
                                            percentageText = item.optString("percentageText"),
                                            attendedClasses = item.optInt("attendedClasses", -1),
                                            totalClasses = item.optInt("totalClasses", -1),
                                            percentage = item.optInt("percentage", -1),
                                        ),
                                    )
                                }
                            }
                        }
                        if (shouldRetryAttendanceFetch(entries, payloadSemesterValue)) {
                            return@evaluateJavascript
                        }
                        selectedSemesterValue = payloadSemesterValue ?: selectedSemesterValue
                        lastAppliedSemesterValue = payloadSemesterValue ?: lastAppliedSemesterValue
                        syncSemesterSpinnerSelection(payloadSemesterValue)
                        attendanceFetchRetryAttempts = 0
                        pendingSemesterValue = null
                        pendingBaselineSignature = null
                        showSemesterStatus(null)
                        val averageFromPayload = payload.optString("averageText").trim().ifBlank { null }
                        renderAttendance(
                            entries = entries,
                            averageLabel = averageFromPayload ?: buildAverageAttendanceLabel(entries),
                        )
                    }

                    "pending_semester" -> {
                        val pendingValue =
                            (pendingSemesterValue ?: selectedSemesterValue ?: lastAppliedSemesterValue)
                                ?.trim()
                                .orEmpty()
                        val option = semesterOptions.firstOrNull {
                            it.value.equals(pendingValue, ignoreCase = true)
                        }
                        if (option != null) {
                            applySemesterSelection(option, forceRefresh = true)
                            return@evaluateJavascript
                        }
                        if (scheduleAttendanceFetchRetry()) {
                            return@evaluateJavascript
                        }
                        showSemesterStatus(null)
                        renderAttendance(emptyList(), getString(R.string.attendance_average_default))
                    }

                    "prelogin" -> redirectToLogin()
                    else -> {
                        if (scheduleAttendanceFetchRetry()) {
                            return@evaluateJavascript
                        }
                        pendingSemesterValue = null
                        pendingBaselineSignature = null
                        showSemesterStatus(null)
                        renderAttendance(emptyList(), getString(R.string.attendance_average_default))
                    }
                }
            }
        }
    }

    private fun shouldRetryAttendanceFetch(
        entries: List<AttendanceEntry>,
        payloadSemesterValue: String?,
    ): Boolean {
        val expectedSemester = (pendingSemesterValue ?: lastAppliedSemesterValue)?.trim().orEmpty()
        val actualSemester = payloadSemesterValue?.trim().orEmpty()
        val currentSignature = if (entries.isNotEmpty()) buildAttendanceSignature(entries) else ""
        val waitingForSemester =
            expectedSemester.isNotBlank() &&
                actualSemester.isNotBlank() &&
                !expectedSemester.equals(actualSemester, ignoreCase = true)
        val staleBySignature =
            pendingSemesterValue != null &&
                !pendingBaselineSignature.isNullOrBlank() &&
                entries.isNotEmpty() &&
                currentSignature == pendingBaselineSignature
        val missingSemesterButStillStale =
            pendingSemesterValue != null &&
                actualSemester.isBlank() &&
                (
                    entries.isEmpty() ||
                        (
                            !pendingBaselineSignature.isNullOrBlank() &&
                                currentSignature == pendingBaselineSignature
                            )
                    )
        return when {
            waitingForSemester -> scheduleAttendanceFetchRetry()
            staleBySignature -> scheduleAttendanceFetchRetry()
            missingSemesterButStillStale -> scheduleAttendanceFetchRetry()
            entries.isEmpty() -> scheduleAttendanceFetchRetry()
            else -> false
        }
    }

    private fun buildAttendanceSignature(entries: List<AttendanceEntry>): String {
        return entries.joinToString(separator = "||") { entry ->
            listOf(
                entry.courseCode.trim().uppercase(),
                entry.subjectTitle.trim(),
                entry.classDetail.trim(),
                entry.facultyDetail.trim(),
                entry.attendedClasses.toString(),
                entry.totalClasses.toString(),
                entry.percentage.toString(),
            ).joinToString("::")
        }
    }

    private fun buildAverageAttendanceLabel(entries: List<AttendanceEntry>): String {
        if (entries.isEmpty()) {
            return getString(R.string.attendance_average_default)
        }
        val validTotals = entries.map { entry ->
            val attended = if (entry.attendedClasses >= 0) entry.attendedClasses else null
            val total = if (entry.totalClasses > 0) entry.totalClasses else null
            if (attended == null || total == null) null else attended to total
        }.filterNotNull()

        val percentage = if (validTotals.isNotEmpty()) {
            val totalAttended = validTotals.sumOf { it.first }
            val totalClasses = validTotals.sumOf { it.second }
            if (totalClasses > 0) {
                ((totalAttended * 100.0) / totalClasses).roundToInt()
            } else {
                -1
            }
        } else {
            val itemPercentages = entries.mapNotNull { entry ->
                if (entry.percentage in 0..100) entry.percentage else null
            }
            if (itemPercentages.isEmpty()) {
                -1
            } else {
                itemPercentages.average().roundToInt()
            }
        }

        return if (percentage < 0) {
            getString(R.string.attendance_average_default)
        } else {
            getString(R.string.attendance_average_format, percentage.coerceIn(0, 100))
        }
    }

    private fun renderAttendance(entries: List<AttendanceEntry>, averageLabel: String) {
        lastRenderedAttendanceSignature = buildAttendanceSignature(entries)
        averageAttendanceText.text = averageLabel
        averageAttendanceText.isVisible = true
        attendanceAdapter.submit(entries)
        attendanceList.isVisible = entries.isNotEmpty()
        emptyText.isVisible = entries.isEmpty()
        emptyText.text = if (entries.isEmpty()) {
            getString(R.string.attendance_no_data)
        } else {
            getString(R.string.attendance_loading)
        }
    }

    private fun showSemesterStatus(messageResId: Int?) {
        if (messageResId == null) {
            semesterStatusText.isVisible = false
            return
        }
        semesterStatusText.isVisible = true
        semesterStatusText.text = getString(messageResId)
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

    private fun buildAttendanceNavigationScript(attemptNumber: Int): String {
        val routeTokensLiteral = toJsArrayLiteral(ATTENDANCE_ROUTE_TOKENS)
        val fallbackPathsLiteral = toJsArrayLiteral(ATTENDANCE_FALLBACK_PATHS, lowercase = false)
        val menuIdsLiteral = toJsArrayLiteral(ATTENDANCE_MENU_IDS)
        val textTermsLiteral = toJsArrayLiteral(ATTENDANCE_TEXT_TERMS)
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
            
              const hasAttendanceForm = docs.some(doc => (
                !!doc.querySelector('form#viewStudentAttendance,select#semesterSubId,select[name="semesterSubId"],#AttendanceDetailDataTable')
              ));
              const currentPath = normalizePath(window.location.pathname + window.location.search);
              if (hasAttendanceForm || routeTokens.some(token => currentPath.includes(token))) {
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

    private fun buildApplySemesterScript(option: SemesterOption): String {
        val valueLiteral = escapeForJs(option.value)
        val labelLiteral = escapeForJs(option.label)
        return """
            (function () {
              const targetValue = '$valueLiteral';
              const targetLabel = '$labelLiteral';
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
            
              const docs = getDocuments();
              if (docs.some(doc => hasPreloginState(doc))) {
                return 'prelogin';
              }
            
              let bestSelect = null;
              let bestDoc = null;
              let bestScore = -1;
              docs.forEach(doc => {
                Array.from(doc.querySelectorAll('select')).forEach(select => {
                  const id = normalizeLower(select.id || '');
                  const name = normalizeLower(select.name || '');
                  const options = Array.from(select.options || []);
                  let score = 0;
                  const inAttendanceForm = !!select.closest('form#viewStudentAttendance');
                  if (inAttendanceForm) score += 1000;
                  if (id === 'semestersubid' || name === 'semestersubid') score += 40;
                  if (id.includes('semestersubid') || name.includes('semestersubid')) score += 20;
                  if (id.includes('sem') || name.includes('sem')) score += 8;
                  if (options.length > 1) score += 2;
                  if (options.some(opt => normalizeLower(opt.textContent).includes('sem'))) score += 4;
                  if (options.some(opt => {
                    const value = normalize(opt.value || '');
                    const label = normalize(opt.textContent || '');
                    if (targetValue && value === targetValue) return true;
                    if (targetLabel && normalizeLower(label) === normalizeLower(targetLabel)) return true;
                    return !!targetLabel && normalizeLower(label).includes(normalizeLower(targetLabel));
                  })) {
                    score += 16;
                  }
                  if (score > bestScore) {
                    bestSelect = select;
                    bestDoc = doc;
                    bestScore = score;
                  }
                });
              });
            
              if (!bestSelect || !bestDoc) {
                return 'select_not_found';
              }
            
              const options = Array.from(bestSelect.options || []);
              const targetOption = options.find(opt => {
                const value = normalize(opt.value || '');
                const label = normalize(opt.textContent || '');
                if (targetValue && value === targetValue) {
                  return true;
                }
                return !!targetLabel && normalizeLower(label) === normalizeLower(targetLabel);
              }) || options.find(opt => {
                const label = normalize(opt.textContent || '');
                return !!targetLabel && normalizeLower(label).includes(normalizeLower(targetLabel));
              });
            
              if (!targetOption) {
                return 'option_not_found';
              }
            
              bestSelect.value = targetOption.value;
              targetOption.selected = true;
              try {
                bestSelect.dispatchEvent(new Event('input', { bubbles: true }));
                bestSelect.dispatchEvent(new Event('change', { bubbles: true }));
              } catch (_) {
                // Continue with submit fallbacks.
              }
            
              const hosts = [bestDoc.defaultView, window, window.parent];
              for (let i = 0; i < hosts.length; i++) {
                try {
                  const host = hosts[i];
                  if (host && typeof host.processStudentAttendance === 'function') {
                    host.processStudentAttendance();
                    return 'submitted';
                  }
                } catch (_) {
                  // Ignore cross-origin access issues.
                }
              }
            
              const submitNode = Array.from(bestDoc.querySelectorAll('button,input[type="submit"],a[onclick],a[href]')).find(node => {
                const text = normalizeLower(node.innerText || node.textContent || node.value || '');
                return text.includes('search') || text.includes('show') || text.includes('view') || text.includes('submit') || text.includes('go');
              });
              if (submitNode && typeof submitNode.click === 'function') {
                submitNode.click();
                return 'submitted';
              }
            
              const form = bestSelect.form || bestSelect.closest('form');
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

    private fun buildMetaLine(item: AttendanceEntry): String {
        val code = item.courseCode.trim().ifBlank { item.classGroup.trim() }
        val classDetail = item.classDetail.trim().ifBlank { item.classGroup.trim() }
        return when {
            code.isBlank() && classDetail.isBlank() -> item.facultyDetail.trim()
            code.isBlank() -> classDetail
            classDetail.isBlank() -> code
            else -> "$code - $classDetail"
        }
    }

    private fun displayCount(number: Int, fallbackText: String): String {
        return if (number >= 0) {
            number.toString()
        } else {
            fallbackText.trim().ifBlank { getString(R.string.marks_value_dash) }
        }
    }

    private inner class AttendanceAdapter : RecyclerView.Adapter<AttendanceViewHolder>() {
        private val items = mutableListOf<AttendanceEntry>()

        fun submit(newItems: List<AttendanceEntry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttendanceViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_attendance_entry, parent, false)
            return AttendanceViewHolder(view as ViewGroup)
        }

        override fun onBindViewHolder(holder: AttendanceViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class AttendanceViewHolder(root: ViewGroup) : RecyclerView.ViewHolder(root) {
        private val titleText: TextView = root.findViewById(R.id.attendanceSubjectTitleText)
        private val metaText: TextView = root.findViewById(R.id.attendanceMetaText)
        private val countsText: TextView = root.findViewById(R.id.attendanceCountsText)
        private val percentText: TextView = root.findViewById(R.id.attendancePercentText)
        private val percentIndicator: CircularProgressIndicator =
            root.findViewById(R.id.attendancePercentIndicator)

        fun bind(item: AttendanceEntry) {
            titleText.text = item.subjectTitle.trim().ifBlank {
                item.courseDetail.trim().ifBlank { getString(R.string.marks_value_na) }
            }
            metaText.text = buildMetaLine(item)
            countsText.text = getString(
                R.string.attendance_counts_format,
                displayCount(item.totalClasses, item.totalClassesText),
                displayCount(item.attendedClasses, item.attendedClassesText),
            )
            val normalizedPercent = item.percentage.coerceIn(0, 100)
            percentText.text = getString(R.string.attendance_percent_format, normalizedPercent)
            percentIndicator.progress = normalizedPercent
        }
    }

    private data class SemesterOption(
        val label: String,
        val value: String,
    )

    private data class AttendanceEntry(
        val subjectTitle: String,
        val courseDetail: String,
        val courseCode: String,
        val courseType: String,
        val classGroup: String,
        val classDetail: String,
        val facultyDetail: String,
        val attendedClassesText: String,
        val totalClassesText: String,
        val percentageText: String,
        val attendedClasses: Int,
        val totalClasses: Int,
        val percentage: Int,
    )

    private companion object {
        private const val HOME_URL = "https://vtop.vit.ac.in/vtop/content"

        private val ATTENDANCE_ROUTE_TOKENS = listOf(
            "academics/common/studentattendance",
            "academics/studentattendance",
            "academics/common/attendance",
        )
        private val ATTENDANCE_FALLBACK_PATHS = listOf(
            "academics/common/StudentAttendance",
            "academics/common/studentattendance",
            "academics/studentattendance",
        )
        private val ATTENDANCE_MENU_IDS = listOf("ACD0042")
        private val ATTENDANCE_TEXT_TERMS = listOf(
            "attendance",
            "class attendance",
            "attendance report",
        )

        private const val JS_STATE_DONE = "done"
        private const val JS_STATE_PRELOGIN = "prelogin"
        private const val JS_STATE_NOT_FOUND_PAGE = "not_found_page"
        private const val APPLY_STATE_CHANGED = "changed"
        private const val APPLY_STATE_SUBMITTED = "submitted"
        private const val APPLY_STATE_DONE = "done"

        private const val MAX_NAVIGATION_ATTEMPTS = 12
        private const val NAVIGATION_RETRY_DELAY_MS = 700L
        private const val MAX_SEMESTER_FETCH_RETRIES = 10
        private const val SEMESTER_FETCH_RETRY_DELAY_MS = 900L
        private const val MAX_ATTENDANCE_FETCH_RETRIES = 14
        private const val ATTENDANCE_FETCH_RETRY_DELAY_MS = 1000L

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

        private const val EXTRACT_ATTENDANCE_SEMESTERS_SCRIPT =
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
            
              const docs = getDocuments();
              if (docs.some(doc => hasPreloginState(doc))) {
                return JSON.stringify({ status: 'prelogin' });
              }
            
              const candidates = [];
              docs.forEach(doc => {
                const form = doc.querySelector('form#viewStudentAttendance');
                if (!form) {
                  return;
                }
                const select = form.querySelector('select#semesterSubId,select[name="semesterSubId"]');
                if (!select) {
                  return;
                }
            
                const optionCount = Array.from(select.options || [])
                  .filter(option => normalize(option.value).length > 0).length;
                const rowCount = form.querySelectorAll('#AttendanceDetailDataTable tbody tr').length;
                let score = optionCount;
                if (rowCount > 0) score += Math.min(rowCount, 25);
                try {
                  if (form.getClientRects().length > 0) {
                    score += 5;
                  }
                } catch (_) {
                  // Ignore visibility read failures.
                }
                candidates.push({ select: select, score: score });
              });
            
              if (candidates.length === 0) {
                return JSON.stringify({ status: 'not_found' });
              }
              candidates.sort((a, b) => b.score - a.score);
              const bestSelect = candidates[0].select;
            
              if (!bestSelect) {
                return JSON.stringify({ status: 'not_found' });
              }
            
              const options = Array.from(bestSelect.options || [])
                .map(option => ({
                  value: normalize(option.value),
                  label: normalize(option.textContent),
                  selected: !!option.selected,
                }))
                .filter(option => (
                  option.label.length > 0 &&
                  option.value.length > 0 &&
                  !normalizeLower(option.label).includes('choose semester')
                ));
            
              const selectedControlValue = normalize(bestSelect.value || '');
              const selected = options.find(option => option.value === selectedControlValue)
                || options.find(option => option.selected && option.value.length > 0)
                || options[0]
                || null;
            
              return JSON.stringify({
                status: 'ok',
                selectedValue: selected ? selected.value : '',
                options: options,
              });
            })();
            """

        private const val EXTRACT_ATTENDANCE_ENTRIES_SCRIPT =
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
            
              const parseIntValue = (value) => {
                const match = normalize(value).match(/-?\d+/);
                if (!match) {
                  return -1;
                }
                const parsed = parseInt(match[0], 10);
                return Number.isFinite(parsed) ? parsed : -1;
              };
            
              const parsePercentage = (value) => {
                const match = normalize(value).match(/-?\d+(?:\.\d+)?/);
                if (!match) {
                  return -1;
                }
                const parsed = Math.round(parseFloat(match[0]));
                if (!Number.isFinite(parsed)) {
                  return -1;
                }
                return Math.max(0, Math.min(100, parsed));
              };
            
              const parseCourseBits = (courseDetail) => {
                const raw = normalize(courseDetail);
                let courseCode = '';
                let subjectTitle = raw;
                let courseType = '';
            
                const codeMatch = raw.match(/[A-Z]{2,}\d{2,}[A-Z]*/i);
                if (codeMatch && codeMatch[0]) {
                  courseCode = normalize(codeMatch[0]).toUpperCase();
                }
            
                const parts = raw.split(/\s+-\s+/).map(part => normalize(part)).filter(Boolean);
                if (parts.length >= 3 && courseCode && normalizeLower(parts[0]).includes(normalizeLower(courseCode))) {
                  const lastPart = parts[parts.length - 1];
                  if (lastPart.length <= 4 || /theory|lab|project|skill/i.test(lastPart)) {
                    courseType = lastPart;
                    subjectTitle = parts.slice(1, -1).join(' - ');
                  } else {
                    subjectTitle = parts.slice(1).join(' - ');
                  }
                } else if (parts.length >= 2 && courseCode && normalizeLower(parts[0]).includes(normalizeLower(courseCode))) {
                  subjectTitle = parts.slice(1).join(' - ');
                } else if (parts.length >= 2) {
                  subjectTitle = parts.slice(1).join(' - ') || parts[0];
                }
            
                if (!subjectTitle) {
                  subjectTitle = raw;
                }
            
                return {
                  courseCode: normalize(courseCode),
                  subjectTitle: normalize(subjectTitle),
                  courseType: normalize(courseType)
                };
              };
            
              const readSelectedSemester = (form, select) => {
                const semesterSelect = select
                  || (form && form.querySelector('select#semesterSubId,select[name="semesterSubId"]'))
                  || null;
                if (!semesterSelect) {
                  return { value: '', label: '' };
                }
                const options = Array.from(semesterSelect.options || []);
                const directValue = normalize(semesterSelect.value || '');
                if (directValue) {
                  const matchedOption = options.find(option => normalize(option.value) === directValue) || null;
                  return {
                    value: directValue,
                    label: normalize(matchedOption ? (matchedOption.textContent || '') : ''),
                  };
                }
                const selectedOption = options.find(option => option.selected && normalize(option.value).length > 0)
                  || options[semesterSelect.selectedIndex]
                  || null;
                if (!selectedOption) {
                  return { value: '', label: '' };
                }
                return {
                  value: normalize(selectedOption.value || ''),
                  label: normalize(selectedOption.textContent || ''),
                };
              };
            
              const docs = getDocuments();
              if (docs.some(doc => hasPreloginState(doc))) {
                return JSON.stringify({ status: 'prelogin' });
              }
            
              const expectedSemesterValue = normalize(window.__attendanceExpectedSemester || '');
              let preferredSemesterValue = expectedSemesterValue;
              if (!preferredSemesterValue) {
                docs.some(doc => {
                  const form = doc.querySelector('form#viewStudentAttendance');
                  const select = (form && form.querySelector('select#semesterSubId,select[name="semesterSubId"]'))
                    || doc.querySelector('select#semesterSubId,select[name="semesterSubId"]');
                  if (!select) {
                    return false;
                  }
                  const value = normalize(select.value || '');
                  if (value) {
                    preferredSemesterValue = value;
                    return true;
                  }
                  const options = Array.from(select.options || []);
                  const selectedOption = options.find(option => option.selected && normalize(option.value).length > 0)
                    || options[select.selectedIndex]
                    || null;
                  const fallbackValue = normalize(selectedOption ? selectedOption.value : '');
                  if (fallbackValue) {
                    preferredSemesterValue = fallbackValue;
                    return true;
                  }
                  return false;
                });
              }
            
              const candidates = [];
              docs.forEach(doc => {
                const form = doc.querySelector('form#viewStudentAttendance');
                const select = (form && form.querySelector('select#semesterSubId,select[name="semesterSubId"]'))
                  || doc.querySelector('select#semesterSubId,select[name="semesterSubId"]');
                const semester = readSelectedSemester(form, select);
                const attendanceTable = (form && form.querySelector('#AttendanceDetailDataTable'))
                  || doc.querySelector('#AttendanceDetailDataTable');
            
                let rows = [];
                if (attendanceTable) {
                  rows = Array.from(attendanceTable.querySelectorAll('tbody tr'));
                  const host = doc.defaultView || window;
                  const jq = host && host.jQuery;
                  if (
                    jq &&
                    jq.fn &&
                    jq.fn.dataTable &&
                    (
                      jq.fn.dataTable.isDataTable(attendanceTable) ||
                      jq.fn.dataTable.isDataTable('#AttendanceDetailDataTable')
                    )
                  ) {
                    try {
                      const tableApi = jq.fn.dataTable.isDataTable(attendanceTable)
                        ? jq(attendanceTable).DataTable()
                        : jq('#AttendanceDetailDataTable').DataTable();
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
                  if (cells.length < 8) {
                    return;
                  }
            
                  const texts = cells.map(cell => normalize(cell.innerText || cell.textContent));
                  const rowText = normalizeLower(texts.join(' '));
                  if (
                    !rowText ||
                    rowText.includes('course detail') ||
                    rowText.includes('attendance percentage') ||
                    rowText.includes('attended classes')
                  ) {
                    return;
                  }
            
                  const classGroup = texts[1] || '';
                  const courseDetail = texts[2] || '';
                  const classDetail = texts[3] || '';
                  const facultyDetail = texts[4] || '';
                  const attendedClassesText = texts[5] || '';
                  const totalClassesText = texts[6] || '';
                  const percentageText = texts[7] || '';
            
                  if (!courseDetail && !classDetail) {
                    return;
                  }
            
                  const bits = parseCourseBits(courseDetail);
                  entries.push({
                    subjectTitle: bits.subjectTitle || '',
                    courseDetail: courseDetail,
                    courseCode: bits.courseCode || '',
                    courseType: bits.courseType || '',
                    classGroup: classGroup,
                    classDetail: classDetail,
                    facultyDetail: facultyDetail,
                    attendedClassesText: attendedClassesText,
                    totalClassesText: totalClassesText,
                    percentageText: percentageText,
                    attendedClasses: parseIntValue(attendedClassesText),
                    totalClasses: parseIntValue(totalClassesText),
                    percentage: parsePercentage(percentageText),
                  });
                });
            
                let score = entries.length * 100;
                if (semester.value.length > 0) score += 10;
                if (preferredSemesterValue && semester.value) {
                  if (normalizeLower(semester.value) === normalizeLower(preferredSemesterValue)) {
                    score += 450;
                  } else {
                    score -= 220;
                  }
                } else if (preferredSemesterValue && !semester.value) {
                  score -= 120;
                }
                try {
                  if (form && form.getClientRects().length > 0) {
                    score += 20;
                  }
                  if (attendanceTable && attendanceTable.getClientRects().length > 0) {
                    score += 20;
                  }
                } catch (_) {
                  // Ignore visibility read failures.
                }
                if (doc === document) score += 5;
                if (attendanceTable) score += 5;
                candidates.push({
                  semesterValue: semester.value,
                  semesterLabel: semester.label,
                  entries: entries,
                  score: score
                });
              });
            
              if (candidates.length === 0) {
                return JSON.stringify({ status: 'not_found' });
              }
            
              candidates.sort((a, b) => b.score - a.score);
              const matchingCandidates = preferredSemesterValue
                ? candidates.filter(candidate => normalizeLower(candidate.semesterValue || '') === normalizeLower(preferredSemesterValue))
                : [];
              if (matchingCandidates.length > 0) {
                matchingCandidates.sort((a, b) => b.score - a.score);
              }
              const best = matchingCandidates.length > 0 ? matchingCandidates[0] : candidates[0];
              if (
                preferredSemesterValue &&
                best &&
                normalizeLower(best.semesterValue || '') !== normalizeLower(preferredSemesterValue)
              ) {
                return JSON.stringify({
                  status: 'pending_semester',
                  selectedSemesterValue: best.semesterValue || '',
                  selectedSemesterLabel: best.semesterLabel || '',
                  entries: []
                });
              }
              const entries = (best.entries || []).filter(entry => {
                return !!(
                  normalize(entry.subjectTitle).length > 0 ||
                  normalize(entry.courseDetail).length > 0 ||
                  normalize(entry.classDetail).length > 0
                );
              });
            
              const validTotals = entries.filter(entry => entry.totalClasses > 0 && entry.attendedClasses >= 0);
              let averageText = '';
              if (validTotals.length > 0) {
                const totalClasses = validTotals.reduce((sum, entry) => sum + entry.totalClasses, 0);
                const attendedClasses = validTotals.reduce((sum, entry) => sum + entry.attendedClasses, 0);
                if (totalClasses > 0) {
                  const weighted = Math.round((attendedClasses * 100) / totalClasses);
                  averageText = 'Average Attendance - ' + Math.max(0, Math.min(100, weighted)) + '%';
                }
              }
            
              return JSON.stringify({
                status: 'ok',
                selectedSemesterValue: best.semesterValue || '',
                selectedSemesterLabel: best.semesterLabel || '',
                averageText: averageText,
                entries: entries
              });
            })();
            """
    }
}

