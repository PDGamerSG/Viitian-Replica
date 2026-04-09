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

class CoursePageCoursesActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var semesterSpinner: Spinner
    private lateinit var semesterStatusText: TextView
    private lateinit var subjectList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var semesterAdapter: ArrayAdapter<String>
    private lateinit var subjectsAdapter: CourseSubjectsAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val semesterOptions = mutableListOf<SemesterOption>()
    private val allSubjects = mutableListOf<CourseSubjectEntry>()

    private var navigationAttempts = 0
    private var navigationInProgress = false
    private var dataFetchInProgress = false
    private var dataFetchRetryAttempts = 0
    private var suppressSemesterSelection = false
    private var loginRedirectHandled = false
    private var selectedSemesterValue: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_course_page_courses)
        setSupportActionBar(findViewById(R.id.topAppBar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progressBar = findViewById(R.id.pageLoadProgress)
        swipeRefreshLayout = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)
        semesterSpinner = findViewById(R.id.semesterSpinner)
        semesterStatusText = findViewById(R.id.semesterStatusText)
        subjectList = findViewById(R.id.subjectList)
        emptyText = findViewById(R.id.emptyText)

        subjectsAdapter = CourseSubjectsAdapter { subject ->
            openSubjectDetails(subject)
        }
        subjectList.layoutManager = LinearLayoutManager(this)
        subjectList.adapter = subjectsAdapter

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
                selectedSemesterValue = option.value
                renderFilteredSubjects()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        swipeRefreshLayout.setColorSchemeResources(R.color.marks_indicator)
        swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.marks_surface)
        swipeRefreshLayout.setOnRefreshListener {
            navigationAttempts = 0
            dataFetchRetryAttempts = 0
            selectedSemesterValue = null
            allSubjects.clear()
            subjectsAdapter.submit(emptyList())
            semesterSpinner.isEnabled = false
            showSemesterStatus(R.string.course_page_semester_loading)
            emptyText.text = getString(R.string.course_page_courses_loading)
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
                        this@CoursePageCoursesActivity,
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
            showSemesterStatus(R.string.course_page_semester_loading)
            subjectList.isVisible = false
            emptyText.isVisible = true
            emptyText.text = getString(R.string.course_page_courses_loading)
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
                ensureCoursePageReady()
            }
        }
    }

    private fun ensureCoursePageReady() {
        if (navigationInProgress) {
            return
        }
        if (navigationAttempts >= MAX_NAVIGATION_ATTEMPTS) {
            emptyText.text = getString(R.string.course_page_courses_unavailable)
            showSemesterStatus(R.string.course_page_semester_unavailable)
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        navigationInProgress = true
        navigationAttempts += 1
        webView.evaluateJavascript(buildCoursePageNavigationScript(navigationAttempts)) { rawResult ->
            navigationInProgress = false
            when (parseJsResult(rawResult)) {
                JS_STATE_DONE -> fetchSubjects()
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
            emptyText.text = getString(R.string.course_page_courses_unavailable)
            showSemesterStatus(R.string.course_page_semester_unavailable)
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        mainHandler.postDelayed(
            { webView.loadUrl(HOME_URL) },
            NAVIGATION_RETRY_DELAY_MS,
        )
    }

    private fun fetchSubjects() {
        if (dataFetchInProgress) {
            return
        }
        dataFetchInProgress = true
        webView.evaluateJavascript(EXTRACT_COURSE_SUBJECTS_SCRIPT) { rawResult ->
            dataFetchInProgress = false
            val parsed = parseJsResult(rawResult, lowercase = false)
            if (parsed.isBlank()) {
                if (scheduleSubjectsRetry()) {
                    return@evaluateJavascript
                }
                showSemesterStatus(R.string.course_page_semester_unavailable)
                renderSubjects(emptyList())
                return@evaluateJavascript
            }

            val payload = runCatching { JSONObject(parsed) }.getOrNull()
            if (payload == null) {
                if (scheduleSubjectsRetry()) {
                    return@evaluateJavascript
                }
                showSemesterStatus(R.string.course_page_semester_unavailable)
                renderSubjects(emptyList())
                return@evaluateJavascript
            }

            when (payload.optString("status")) {
                "ok" -> {
                    val subjectsArray = payload.optJSONArray("subjects")
                    val subjects = buildList {
                        if (subjectsArray != null) {
                            for (index in 0 until subjectsArray.length()) {
                                val item = subjectsArray.optJSONObject(index) ?: continue
                                val courseId = item.optString("courseId").trim()
                                val rawLabel = item.optString("rawLabel").trim()
                                if (courseId.isBlank() && rawLabel.isBlank()) {
                                    continue
                                }
                                add(
                                    CourseSubjectEntry(
                                        courseId = courseId,
                                        courseCode = item.optString("courseCode"),
                                        courseTitle = item.optString("courseTitle"),
                                        courseType = item.optString("courseType"),
                                        semesterValue = item.optString("semesterValue"),
                                        semesterLabel = item.optString("semesterLabel"),
                                        rawLabel = rawLabel,
                                    ),
                                )
                            }
                        }
                    }

                    if (subjects.isEmpty()) {
                        if (scheduleSubjectsRetry()) {
                            return@evaluateJavascript
                        }
                        showSemesterStatus(R.string.course_page_semester_unavailable)
                        renderSubjects(emptyList())
                        return@evaluateJavascript
                    }

                    dataFetchRetryAttempts = 0
                    allSubjects.clear()
                    allSubjects.addAll(subjects)

                    val semesters = buildSemesterOptions(subjects)
                    val selectedFromPayload = payload.optString("selectedSemesterValue").trim().ifEmpty { null }
                    showSemesterOptions(semesters, selectedFromPayload)
                    renderFilteredSubjects()
                }

                "prelogin" -> redirectToLogin()
                else -> {
                    if (scheduleSubjectsRetry(retryNavigation = true)) {
                        return@evaluateJavascript
                    }
                    showSemesterStatus(R.string.course_page_semester_unavailable)
                    renderSubjects(emptyList())
                }
            }
        }
    }

    private fun scheduleSubjectsRetry(retryNavigation: Boolean = false): Boolean {
        if (dataFetchRetryAttempts >= MAX_SUBJECT_FETCH_RETRIES) {
            return false
        }
        dataFetchRetryAttempts += 1
        mainHandler.postDelayed(
            {
                if (retryNavigation) {
                    ensureCoursePageReady()
                } else {
                    fetchSubjects()
                }
            },
            SUBJECT_FETCH_RETRY_DELAY_MS,
        )
        return true
    }

    private fun buildSemesterOptions(subjects: List<CourseSubjectEntry>): List<SemesterOption> {
        val byValue = linkedMapOf<String, String>()
        subjects.forEach { subject ->
            val value = subject.semesterValue.trim().ifBlank { subject.semesterLabel.trim() }
            val label = subject.semesterLabel.trim().ifBlank { value }
            if (value.isBlank() || label.isBlank()) {
                return@forEach
            }
            byValue.putIfAbsent(value, label)
        }
        return byValue.map { (value, label) -> SemesterOption(label = label, value = value) }
    }

    private fun showSemesterOptions(options: List<SemesterOption>, selectedFromPayload: String?) {
        semesterOptions.clear()
        semesterOptions.addAll(options)

        suppressSemesterSelection = true
        semesterAdapter.clear()
        semesterAdapter.addAll(options.map { it.label })
        semesterAdapter.notifyDataSetChanged()

        val preferredValue = selectedSemesterValue ?: selectedFromPayload ?: options.firstOrNull()?.value
        val selectedIndex = options.indexOfFirst { it.value.equals(preferredValue, ignoreCase = true) }
            .let { if (it >= 0) it else 0 }
        semesterSpinner.setSelection(selectedIndex, false)
        semesterSpinner.isEnabled = options.isNotEmpty()
        selectedSemesterValue = options.getOrNull(selectedIndex)?.value

        suppressSemesterSelection = false
        showSemesterStatus(null)
    }

    private fun renderFilteredSubjects() {
        val expectedSemester = selectedSemesterValue?.trim().orEmpty()
        val filtered = if (expectedSemester.isBlank()) {
            allSubjects.toList()
        } else {
            allSubjects.filter { subject ->
                subject.semesterValue.trim().equals(expectedSemester, ignoreCase = true) ||
                    (
                        subject.semesterValue.isBlank() &&
                            subject.semesterLabel.trim().equals(expectedSemester, ignoreCase = true)
                        )
            }
        }
        renderSubjects(filtered)
    }

    private fun renderSubjects(subjects: List<CourseSubjectEntry>) {
        subjectsAdapter.submit(subjects)
        subjectList.isVisible = subjects.isNotEmpty()
        emptyText.isVisible = subjects.isEmpty()
        emptyText.text = if (subjects.isEmpty()) {
            getString(R.string.course_page_no_courses)
        } else {
            getString(R.string.course_page_courses_loading)
        }
    }

    private fun openSubjectDetails(subject: CourseSubjectEntry) {
        startActivity(
            Intent(this, CoursePageMaterialsActivity::class.java).apply {
                putExtra(CoursePageMaterialsActivity.EXTRA_COURSE_ID, subject.courseId)
                putExtra(CoursePageMaterialsActivity.EXTRA_COURSE_CODE, subject.courseCode)
                putExtra(CoursePageMaterialsActivity.EXTRA_COURSE_TITLE, subject.courseTitle)
                putExtra(CoursePageMaterialsActivity.EXTRA_COURSE_TYPE, subject.courseType)
                putExtra(CoursePageMaterialsActivity.EXTRA_SEMESTER_VALUE, subject.semesterValue)
                putExtra(CoursePageMaterialsActivity.EXTRA_RAW_LABEL, subject.rawLabel)
            },
        )
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

    private fun buildCourseDisplayTitle(item: CourseSubjectEntry): String {
        val title = item.courseTitle.trim().ifBlank {
            item.rawLabel.trim().ifBlank { getString(R.string.marks_value_na) }
        }
        val type = item.courseType.trim()
        val code = item.courseCode.trim()
        return when {
            title.isBlank() -> getString(R.string.marks_value_na)
            type.isBlank() && code.isBlank() -> title
            type.isBlank() -> "$title - $code"
            code.isBlank() -> "$title - $type"
            else -> "$title - $type - $code"
        }
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

    private inner class CourseSubjectsAdapter(
        private val onSubjectClick: (CourseSubjectEntry) -> Unit,
    ) : RecyclerView.Adapter<CourseSubjectViewHolder>() {
        private val items = mutableListOf<CourseSubjectEntry>()

        fun submit(newItems: List<CourseSubjectEntry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseSubjectViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_course_page_subject, parent, false)
            return CourseSubjectViewHolder(view as ViewGroup)
        }

        override fun onBindViewHolder(holder: CourseSubjectViewHolder, position: Int) {
            holder.bind(items[position], onSubjectClick)
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class CourseSubjectViewHolder(root: ViewGroup) : RecyclerView.ViewHolder(root) {
        private val titleText: TextView = root.findViewById(R.id.subjectTitleText)

        fun bind(item: CourseSubjectEntry, onSubjectClick: (CourseSubjectEntry) -> Unit) {
            titleText.text = buildCourseDisplayTitle(item)
            itemView.setOnClickListener { onSubjectClick(item) }
        }
    }

    private data class CourseSubjectEntry(
        val courseId: String,
        val courseCode: String,
        val courseTitle: String,
        val courseType: String,
        val semesterValue: String,
        val semesterLabel: String,
        val rawLabel: String,
    )

    private data class SemesterOption(
        val label: String,
        val value: String,
    )

    private companion object {
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
        private const val MAX_SUBJECT_FETCH_RETRIES = 10
        private const val SUBJECT_FETCH_RETRY_DELAY_MS = 900L

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

        private const val EXTRACT_COURSE_SUBJECTS_SCRIPT =
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
            
              const parseCourseLabel = (rawLabel, rawValue, rawType) => {
                const label = normalize(rawLabel);
                const value = normalize(rawValue);
                const type = normalize(rawType);
                const parts = label.split(/\s+-\s+/).map(part => normalize(part)).filter(Boolean);
                const semesterLabel = parts.length > 0 ? parts[0] : '';
            
                let courseCode = '';
                const valueParts = value.split('_').map(part => normalize(part)).filter(Boolean);
                if (valueParts.length >= 2) {
                  courseCode = valueParts[1];
                } else if (parts.length > 1) {
                  courseCode = parts[1];
                }
            
                let courseTitle = '';
                if (courseCode) {
                  const marker = ' - ' + courseCode + ' - ';
                  const markerIndex = label.indexOf(marker);
                  if (markerIndex >= 0) {
                    const afterCode = label.substring(markerIndex + marker.length);
                    if (type) {
                      const typeMarker = ' - ' + type + ' - ';
                      const typeIndex = afterCode.lastIndexOf(typeMarker);
                      if (typeIndex > 0) {
                        courseTitle = normalize(afterCode.substring(0, typeIndex));
                      } else {
                        const shortTypeIndex = afterCode.lastIndexOf(' - ' + type);
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
                  semesterLabel: semesterLabel,
                  courseCode: courseCode,
                  courseTitle: courseTitle
                };
              };
            
              const docs = getDocuments();
              if (docs.some(doc => hasPreloginState(doc))) {
                return JSON.stringify({ status: 'prelogin' });
              }
            
              const candidates = [];
              docs.forEach(doc => {
                const form = doc.querySelector('form#coursePageView');
                if (!form) {
                  return;
                }
                const select = form.querySelector('select#courseId,select[name="courseId"]');
                if (!select) {
                  return;
                }
            
                const options = Array.from(select.options || [])
                  .map(option => {
                    const optionValue = normalize(option.value);
                    const optionLabel = normalize(option.textContent);
                    if (!optionValue || !optionLabel || normalizeLower(optionLabel).includes('registered courses')) {
                      return null;
                    }
                    const semesterValue = normalize(option.getAttribute('data-semestr') || '');
                    const courseType = normalize(option.getAttribute('data-crstype') || '');
                    const parsed = parseCourseLabel(optionLabel, optionValue, courseType);
                    return {
                      courseId: normalize(option.getAttribute('data-courseid') || optionValue),
                      optionValue: optionValue,
                      rawLabel: optionLabel,
                      semesterValue: semesterValue,
                      semesterLabel: parsed.semesterLabel,
                      courseCode: parsed.courseCode,
                      courseTitle: parsed.courseTitle,
                      courseType: courseType,
                      selected: !!option.selected
                    };
                  })
                  .filter(Boolean);
            
                if (options.length === 0) {
                  return;
                }
            
                const selectedValue = normalize(select.value || '');
                const selected = options.find(item => normalize(item.optionValue) === selectedValue) || options.find(item => item.selected) || options[0];
                let score = options.length * 10;
                if (selected && selected.semesterValue) score += 5;
                if (doc === document) score += 5;
                candidates.push({
                  score: score,
                  selectedSemesterValue: selected ? normalize(selected.semesterValue) : '',
                  selectedCourseId: selected ? normalize(selected.courseId) : '',
                  options: options
                });
              });
            
              if (candidates.length === 0) {
                return JSON.stringify({ status: 'not_found' });
              }
            
              candidates.sort((a, b) => b.score - a.score);
              const best = candidates[0];
              if (!best || !best.options || best.options.length === 0) {
                return JSON.stringify({ status: 'not_found' });
              }
            
              return JSON.stringify({
                status: 'ok',
                selectedSemesterValue: best.selectedSemesterValue || '',
                selectedCourseId: best.selectedCourseId || '',
                subjects: best.options.map(option => ({
                  courseId: option.courseId || option.optionValue || '',
                  courseCode: option.courseCode || '',
                  courseTitle: option.courseTitle || '',
                  courseType: option.courseType || '',
                  semesterValue: option.semesterValue || '',
                  semesterLabel: option.semesterLabel || '',
                  rawLabel: option.rawLabel || ''
                }))
              });
            })();
            """
    }
}
