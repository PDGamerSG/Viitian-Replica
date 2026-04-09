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
import org.json.JSONObject

class DaCoursesActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var semesterSpinner: Spinner
    private lateinit var semesterStatusText: TextView
    private lateinit var courseList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var semesterAdapter: ArrayAdapter<String>
    private lateinit var coursesAdapter: DaCoursesAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val semesterOptions = mutableListOf<SemesterOption>()

    private var navigationAttempts = 0
    private var navigationInProgress = false
    private var semesterFetchInProgress = false
    private var coursesFetchInProgress = false
    private var semesterFetchRetryAttempts = 0
    private var courseFetchRetryAttempts = 0
    private var pendingSemesterValue: String? = null
    private var pendingBaselineSignature: String? = null
    private var lastRenderedCoursesSignature: String = ""
    private var suppressSemesterSelection = false
    private var loginRedirectHandled = false
    private var selectedSemesterValue: String? = null
    private var lastAppliedSemesterValue: String? = null
    private var initialSemesterRefreshPending = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_da_courses)
        setSupportActionBar(findViewById(R.id.topAppBar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progressBar = findViewById(R.id.pageLoadProgress)
        swipeRefreshLayout = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)
        semesterSpinner = findViewById(R.id.semesterSpinner)
        semesterStatusText = findViewById(R.id.semesterStatusText)
        courseList = findViewById(R.id.courseList)
        emptyText = findViewById(R.id.emptyText)

        coursesAdapter = DaCoursesAdapter { course ->
            openCourseDetails(course)
        }
        courseList.layoutManager = LinearLayoutManager(this)
        courseList.adapter = coursesAdapter

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
            courseFetchRetryAttempts = 0
            pendingSemesterValue = null
            pendingBaselineSignature = null
            initialSemesterRefreshPending = true
            showSemesterStatus(R.string.da_semester_loading)
            emptyText.text = getString(R.string.da_courses_loading)
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
                        this@DaCoursesActivity,
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
            showSemesterStatus(R.string.da_semester_loading)
            courseList.isVisible = false
            emptyText.isVisible = true
            emptyText.text = getString(R.string.da_semester_loading)
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
                ensureDaPageReady()
            }
        }
    }

    private fun ensureDaPageReady() {
        if (navigationInProgress) {
            return
        }
        if (navigationAttempts >= MAX_NAVIGATION_ATTEMPTS) {
            emptyText.text = getString(R.string.da_courses_unavailable)
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        navigationInProgress = true
        navigationAttempts += 1
        webView.evaluateJavascript(buildDaNavigationScript(navigationAttempts)) { rawResult ->
            navigationInProgress = false
            when (parseJsResult(rawResult)) {
                JS_STATE_DONE -> fetchSemesterOptions()
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
            emptyText.text = getString(R.string.da_courses_unavailable)
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
        webView.evaluateJavascript(EXTRACT_DA_SEMESTERS_SCRIPT) { rawResult ->
            semesterFetchInProgress = false
            val parsed = parseJsResult(rawResult, lowercase = false)
            if (parsed.isBlank()) {
                if (scheduleSemesterFetchRetry()) {
                    return@evaluateJavascript
                }
                showSemesterStatus(R.string.da_semester_unavailable)
                semesterSpinner.isEnabled = false
                return@evaluateJavascript
            }

            val payload = runCatching { JSONObject(parsed) }.getOrNull()
            if (payload == null) {
                if (scheduleSemesterFetchRetry()) {
                    return@evaluateJavascript
                }
                showSemesterStatus(R.string.da_semester_unavailable)
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
                        showSemesterStatus(R.string.da_semester_unavailable)
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
                    fetchDaCourses()
                }

                "prelogin" -> redirectToLogin()
                else -> {
                    if (scheduleSemesterFetchRetry(retryNavigation = true)) {
                        return@evaluateJavascript
                    }
                    showSemesterStatus(R.string.da_semester_unavailable)
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
        courseFetchRetryAttempts = 0
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
                -> {
                    val previousSemesterValue = lastAppliedSemesterValue
                    val requestedSemesterValue = option.value
                    val semesterChanged =
                        forceRefresh ||
                            (previousSemesterValue != null &&
                                previousSemesterValue != requestedSemesterValue)
                    pendingSemesterValue = if (semesterChanged) requestedSemesterValue else null
                    pendingBaselineSignature =
                        if (semesterChanged) lastRenderedCoursesSignature else null
                    selectedSemesterValue = requestedSemesterValue
                    lastAppliedSemesterValue = requestedSemesterValue
                    semesterFetchRetryAttempts = 0
                    courseFetchRetryAttempts = 0
                    showSemesterStatus(R.string.da_semester_loading)
                    renderCourses(emptyList())
                    mainHandler.postDelayed({ fetchSemesterOptions() }, COURSE_FETCH_RETRY_DELAY_MS)
                }

                JS_STATE_PRELOGIN -> redirectToLogin()
                else -> Toast.makeText(this, R.string.da_semester_apply_failed, Toast.LENGTH_SHORT).show()
            }
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

    private fun scheduleSemesterFetchRetry(retryNavigation: Boolean = false): Boolean {
        if (semesterFetchRetryAttempts >= MAX_SEMESTER_FETCH_RETRIES) {
            return false
        }
        semesterFetchRetryAttempts += 1
        mainHandler.postDelayed(
            {
                if (retryNavigation) {
                    ensureDaPageReady()
                } else {
                    fetchSemesterOptions()
                }
            },
            SEMESTER_FETCH_RETRY_DELAY_MS,
        )
        return true
    }

    private fun shouldRetryCourseFetch(
        courses: List<DaCourseEntry>,
        payloadSemesterValue: String?,
    ): Boolean {
        val expectedSemester = (pendingSemesterValue ?: lastAppliedSemesterValue)?.trim().orEmpty()
        val actualSemester = payloadSemesterValue?.trim().orEmpty()
        val waitingForSemester =
            expectedSemester.isNotBlank() &&
                (actualSemester.isBlank() || !expectedSemester.equals(actualSemester, ignoreCase = true))
        val staleBySignature =
            pendingSemesterValue != null &&
                !pendingBaselineSignature.isNullOrBlank() &&
                courses.isNotEmpty() &&
                buildCoursesSignature(courses) == pendingBaselineSignature
        return when {
            waitingForSemester -> scheduleCourseFetchRetry()
            staleBySignature -> scheduleCourseFetchRetry()
            courses.isEmpty() -> scheduleCourseFetchRetry()
            else -> false
        }
    }

    private fun buildCoursesSignature(courses: List<DaCourseEntry>): String {
        return courses.joinToString(separator = "||") { course ->
            listOf(
                course.classId.trim().uppercase(),
                course.courseCode.trim().uppercase(),
                course.courseTitle.trim(),
                course.courseType.trim(),
            ).joinToString("::")
        }
    }

    private fun scheduleCourseFetchRetry(): Boolean {
        if (courseFetchRetryAttempts >= MAX_COURSE_FETCH_RETRIES) {
            return false
        }
        courseFetchRetryAttempts += 1
        showSemesterStatus(R.string.da_semester_loading)
        mainHandler.postDelayed({ fetchDaCourses() }, COURSE_FETCH_RETRY_DELAY_MS)
        return true
    }

    private fun fetchDaCourses() {
        if (coursesFetchInProgress) {
            return
        }
        coursesFetchInProgress = true
        webView.evaluateJavascript(EXTRACT_DA_COURSES_SCRIPT) { rawResult ->
            coursesFetchInProgress = false
            val parsed = parseJsResult(rawResult, lowercase = false)
            if (parsed.isBlank()) {
                if (scheduleCourseFetchRetry()) {
                    return@evaluateJavascript
                }
                showSemesterStatus(null)
                renderCourses(emptyList())
                return@evaluateJavascript
            }
            val payload = runCatching { JSONObject(parsed) }.getOrNull()
            if (payload == null) {
                if (scheduleCourseFetchRetry()) {
                    return@evaluateJavascript
                }
                showSemesterStatus(null)
                renderCourses(emptyList())
                return@evaluateJavascript
            }
            when (payload.optString("status")) {
                "ok" -> {
                    val payloadSemesterValue =
                        payload.optString("selectedSemesterValue").trim().ifEmpty { null }
                    val coursesArray = payload.optJSONArray("courses")
                    val courses = buildList {
                        if (coursesArray != null) {
                            for (index in 0 until coursesArray.length()) {
                                val item = coursesArray.optJSONObject(index) ?: continue
                                add(
                                    DaCourseEntry(
                                        classId = item.optString("classId"),
                                        courseCode = item.optString("courseCode"),
                                        courseTitle = item.optString("courseTitle"),
                                        courseType = item.optString("courseType"),
                                    ),
                                )
                            }
                        }
                    }
                    if (shouldRetryCourseFetch(courses, payloadSemesterValue)) {
                        return@evaluateJavascript
                    }
                    selectedSemesterValue = payloadSemesterValue ?: selectedSemesterValue
                    lastAppliedSemesterValue = payloadSemesterValue ?: lastAppliedSemesterValue
                    syncSemesterSpinnerSelection(payloadSemesterValue)
                    courseFetchRetryAttempts = 0
                    pendingSemesterValue = null
                    pendingBaselineSignature = null
                    showSemesterStatus(null)
                    renderCourses(courses)
                }

                "prelogin" -> redirectToLogin()
                else -> {
                    if (scheduleCourseFetchRetry()) {
                        return@evaluateJavascript
                    }
                    showSemesterStatus(null)
                    renderCourses(emptyList())
                }
            }
        }
    }

    private fun renderCourses(courses: List<DaCourseEntry>) {
        lastRenderedCoursesSignature = buildCoursesSignature(courses)
        coursesAdapter.submit(courses)
        courseList.isVisible = courses.isNotEmpty()
        emptyText.isVisible = courses.isEmpty()
        emptyText.text = if (courses.isEmpty()) {
            getString(R.string.da_no_courses)
        } else {
            getString(R.string.da_courses_loading)
        }
    }

    private fun openCourseDetails(course: DaCourseEntry) {
        startActivity(
            Intent(this, DaAssessmentsActivity::class.java).apply {
                putExtra(DaAssessmentsActivity.EXTRA_CLASS_ID, course.classId)
                putExtra(DaAssessmentsActivity.EXTRA_COURSE_CODE, course.courseCode)
                putExtra(DaAssessmentsActivity.EXTRA_COURSE_TITLE, course.courseTitle)
                putExtra(DaAssessmentsActivity.EXTRA_COURSE_TYPE, abbreviateCourseType(course.courseType))
                putExtra(DaAssessmentsActivity.EXTRA_SEMESTER_VALUE, selectedSemesterValue)
            },
        )
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

    private fun abbreviateCourseType(rawType: String): String {
        val normalized = rawType.trim().lowercase()
        if (normalized.isBlank()) {
            return ""
        }
        return when {
            normalized.contains("theory") -> "TH"
            normalized.contains("lab") -> "LO"
            normalized.contains("soft") -> "SS"
            normalized.contains("open") -> "OC"
            rawType.trim().length <= 4 -> rawType.trim().uppercase()
            else -> rawType.trim()
        }
    }

    private fun buildApplySemesterScript(option: SemesterOption): String {
        val valueLiteral = escapeForJs(option.value)
        val labelLiteral = escapeForJs(option.label)
        return """
            (function () {
              const targetValue = '$valueLiteral';
              const targetLabel = '$labelLiteral';
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

              const docs = getDocuments();
              if (docs.some(doc => hasPreloginState(doc))) {
                return 'prelogin';
              }

              let targetSelect = null;
              let targetDoc = null;
              const candidates = [];
              docs.forEach(doc => {
                const form = doc.querySelector('form#digitalAssignment');
                if (!form) {
                  return;
                }
                const select = form.querySelector('select#semesterSubId,select[name="semesterSubId"]');
                if (!select) {
                  return;
                }

                const options = Array.from(select.options || []);
                const hasTargetOption = options.some(opt => {
                  const value = (opt.value || '').toString().trim();
                  const label = (opt.textContent || '').toString().trim();
                  if (targetValue && value === targetValue) {
                    return true;
                  }
                  if (targetLabel && normalize(label) === normalize(targetLabel)) {
                    return true;
                  }
                  return !!targetLabel && normalize(label).includes(normalize(targetLabel));
                });

                const rowCount = form.querySelectorAll('tr.tableContent,tr.fixedContent.tableContent').length;
                let score = 0;
                if (hasTargetOption) score += 20;
                if (rowCount > 0) score += Math.min(rowCount, 25);
                try {
                  if (form.getClientRects().length > 0) {
                    score += 5;
                  }
                } catch (_) {
                  // Ignore visibility read failures.
                }

                candidates.push({ doc: doc, select: select, score: score });
              });

              if (candidates.length > 0) {
                candidates.sort((a, b) => b.score - a.score);
                targetDoc = candidates[0].doc;
                targetSelect = candidates[0].select;
              }

              if (!targetSelect || !targetDoc) {
                return 'select_not_found';
              }

              const options = Array.from(targetSelect.options || []);
              const targetOption = options.find(opt => {
                const value = (opt.value || '').toString().trim();
                const label = (opt.textContent || '').toString().trim();
                if (targetValue && value === targetValue) {
                  return true;
                }
                return !!targetLabel && normalize(label) === normalize(targetLabel);
              }) || options.find(opt => normalize(opt.textContent).includes(normalize(targetLabel)));

              if (!targetOption) {
                return 'option_not_found';
              }

              targetSelect.value = targetOption.value;
              targetOption.selected = true;
              targetSelect.dispatchEvent(new Event('input', { bubbles: true }));
              targetSelect.dispatchEvent(new Event('change', { bubbles: true }));

              const host = targetDoc.defaultView || window;
              if (host && typeof host.dAOnChange === 'function') {
                host.dAOnChange();
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

    private fun buildCourseDisplayTitle(item: DaCourseEntry): String {
        val type = abbreviateCourseType(item.courseType)
        val title = item.courseTitle.trim()
        return when {
            title.isBlank() && type.isBlank() -> getString(R.string.marks_value_na)
            type.isBlank() -> title
            else -> "$title - $type"
        }
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

              const hasDaForm = docs.some(doc => !!doc.querySelector('form#digitalAssignment'));
              const currentPath = normalizePath(window.location.pathname + window.location.search);
              if (hasDaForm || routeTokens.some(token => currentPath.includes(token))) {
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

    private inner class DaCoursesAdapter(
        private val onCourseClick: (DaCourseEntry) -> Unit,
    ) : RecyclerView.Adapter<DaCourseViewHolder>() {
        private val items = mutableListOf<DaCourseEntry>()

        fun submit(newItems: List<DaCourseEntry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DaCourseViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_da_course, parent, false)
            return DaCourseViewHolder(view as ViewGroup)
        }

        override fun onBindViewHolder(holder: DaCourseViewHolder, position: Int) {
            holder.bind(items[position], onCourseClick)
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class DaCourseViewHolder(root: ViewGroup) : RecyclerView.ViewHolder(root) {
        private val titleText: TextView = root.findViewById(R.id.courseTitleText)

        fun bind(item: DaCourseEntry, onCourseClick: (DaCourseEntry) -> Unit) {
            titleText.text = buildCourseDisplayTitle(item)
            itemView.setOnClickListener { onCourseClick(item) }
        }
    }

    private data class DaCourseEntry(
        val classId: String,
        val courseCode: String,
        val courseTitle: String,
        val courseType: String,
    )

    private data class SemesterOption(
        val label: String,
        val value: String,
    )

    private companion object {
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
        private const val APPLY_STATE_CHANGED = "changed"
        private const val APPLY_STATE_SUBMITTED = "submitted"
        private const val APPLY_STATE_DONE = "done"

        private const val MAX_NAVIGATION_ATTEMPTS = 12
        private const val NAVIGATION_RETRY_DELAY_MS = 700L
        private const val MAX_SEMESTER_FETCH_RETRIES = 10
        private const val SEMESTER_FETCH_RETRY_DELAY_MS = 900L
        private const val MAX_COURSE_FETCH_RETRIES = 10
        private const val COURSE_FETCH_RETRY_DELAY_MS = 1000L

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

        private const val EXTRACT_DA_SEMESTERS_SCRIPT =
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
                const form = doc.querySelector('form#digitalAssignment');
                if (!form) {
                  return;
                }
                const select = form.querySelector('select#semesterSubId,select[name="semesterSubId"]');
                if (!select) {
                  return;
                }

                const optionCount = Array.from(select.options || [])
                  .filter(option => normalize(option.value).length > 0).length;
                const rowCount = form.querySelectorAll('tr.tableContent,tr.fixedContent.tableContent').length;
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

        private const val EXTRACT_DA_COURSES_SCRIPT =
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

              const readSelectedSemester = (form) => {
                const semesterSelect = form.querySelector('select#semesterSubId,select[name="semesterSubId"]');
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

              const parseCoursesFromForm = (form) => {
                const seen = {};
                const parsedCourses = [];
                const rows = Array.from(form.querySelectorAll(
                  '#fixedTableContainer tr.tableContent,#fixedTableContainer tr.fixedContent.tableContent,tr.tableContent,tr.fixedContent.tableContent'
                ));
                rows.forEach(row => {
                  if (row.querySelector('table')) {
                    return;
                  }
                  const cells = getCells(row);
                  if (cells.length < 4) {
                    return;
                  }
                  const rowText = normalizeLower(cells.join(' '));
                  if (rowText.includes('class nbr') || rowText.includes('course code') || rowText.includes('upload status')) {
                    return;
                  }

                  let classId = cells[1] || '';
                  const courseCode = cells[2] || '';
                  const courseTitle = cells[3] || '';
                  const courseType = cells[6] || '';
                  const actionNode = row.querySelector('[onclick*="myFunction"]');
                  if (actionNode) {
                    const onClick = actionNode.getAttribute('onclick') || '';
                    const match = onClick.match(/myFunction\(['"]([^'"]+)['"]\)/i);
                    if (match && match[1]) {
                      classId = normalize(match[1]);
                    }
                  }
                  if (!classId && !courseCode && !courseTitle) {
                    return;
                  }

                  const key = [classId, courseCode, courseTitle].map(value => normalizeLower(value)).join('|');
                  if (!key || seen[key]) {
                    return;
                  }
                  seen[key] = true;
                  parsedCourses.push({
                    classId: classId,
                    courseCode: courseCode,
                    courseTitle: courseTitle,
                    courseType: courseType
                  });
                });
                return parsedCourses;
              };

              const docs = getDocuments();
              if (docs.some(doc => hasPreloginState(doc))) {
                return JSON.stringify({ status: 'prelogin' });
              }

              const candidates = [];
              docs.forEach(doc => {
                const form = doc.querySelector('form#digitalAssignment');
                if (!form) {
                  return;
                }
                const semester = readSelectedSemester(form);
                const courses = parseCoursesFromForm(form);
                let score = courses.length * 100;
                if (semester.value.length > 0) score += 10;
                if (doc === document) score += 5;
                candidates.push({
                  semesterValue: semester.value,
                  semesterLabel: semester.label,
                  courses: courses,
                  score: score,
                });
              });
              if (candidates.length === 0) {
                return JSON.stringify({ status: 'not_found' });
              }
              candidates.sort((a, b) => b.score - a.score);
              const best = candidates[0];
              if (!best || !best.courses || best.courses.length === 0) {
                return JSON.stringify({ status: 'not_found' });
              }

              return JSON.stringify({
                status: 'ok',
                selectedSemesterValue: best.semesterValue,
                selectedSemesterLabel: best.semesterLabel,
                courses: best.courses
              });
            })();
            """
    }
}
