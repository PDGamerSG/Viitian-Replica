package edu.vit.vtop.replica

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import android.widget.ImageView
import android.widget.LinearLayout
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

class MarksActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var semesterSpinner: Spinner
    private lateinit var semesterStatusText: TextView
    private lateinit var marksSummaryText: TextView
    private lateinit var marksEmptyText: TextView
    private lateinit var marksList: RecyclerView
    private lateinit var semesterAdapter: ArrayAdapter<String>
    private lateinit var marksAdapter: MarksAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val semesterOptions = mutableListOf<SemesterOption>()

    private var marksNavigationAttempts = 0
    private var marksNavigationInProgress = false
    private var semesterFetchInProgress = false
    private var marksFetchInProgress = false
    private var suppressSemesterSelection = false
    private var loginRedirectHandled = false
    private var lastAppliedSemesterValue: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_marks)
        setSupportActionBar(findViewById(R.id.topAppBar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progressBar = findViewById(R.id.pageLoadProgress)
        swipeRefreshLayout = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)
        semesterSpinner = findViewById(R.id.semesterSpinner)
        semesterStatusText = findViewById(R.id.semesterStatusText)
        marksSummaryText = findViewById(R.id.marksSummaryText)
        marksEmptyText = findViewById(R.id.marksEmptyText)
        marksList = findViewById(R.id.marksList)

        marksAdapter = MarksAdapter()
        marksList.layoutManager = LinearLayoutManager(this)
        marksList.adapter = marksAdapter
        swipeRefreshLayout.setColorSchemeResources(R.color.marks_indicator)
        swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.marks_surface)

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

        swipeRefreshLayout.setOnRefreshListener {
            marksNavigationAttempts = 0
            showSemesterStatus(R.string.marks_semester_loading)
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
                        this@MarksActivity,
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
            showSemesterStatus(R.string.marks_semester_loading)
            renderMarks(emptyList(), getString(R.string.marks_summary_default))
            webView.loadUrl(HOME_URL)
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
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
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
                ensureMarksPageReady()
            }
        }
    }

    private fun ensureMarksPageReady() {
        if (marksNavigationInProgress) {
            return
        }
        if (marksNavigationAttempts >= MAX_NAVIGATION_ATTEMPTS) {
            semesterSpinner.isEnabled = false
            showSemesterStatus(R.string.marks_semester_unavailable)
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        marksNavigationInProgress = true
        marksNavigationAttempts += 1
        webView.evaluateJavascript(buildMarksNavigationScript(marksNavigationAttempts)) { rawResult ->
            marksNavigationInProgress = false
            when (parseJsResult(rawResult)) {
                JS_STATE_DONE -> fetchSemesterOptions()
                JS_STATE_PRELOGIN -> redirectToLogin()
                JS_STATE_NOT_FOUND_PAGE -> recoverFromNotFoundPage()
                else -> {
                    mainHandler.postDelayed(
                        { ensureMarksPageReady() },
                        NAVIGATION_RETRY_DELAY_MS,
                    )
                }
            }
        }
    }

    private fun fetchSemesterOptions() {
        if (semesterFetchInProgress) {
            return
        }
        semesterFetchInProgress = true
        webView.evaluateJavascript(EXTRACT_SEMESTERS_SCRIPT) { rawResult ->
            semesterFetchInProgress = false
            val parsed = parseJsResult(rawResult, lowercase = false)
            if (parsed.isBlank()) {
                mainHandler.postDelayed({ fetchSemesterOptions() }, SEMESTER_RETRY_DELAY_MS)
                return@evaluateJavascript
            }

            val payload = runCatching { JSONObject(parsed) }.getOrNull()
            if (payload == null) {
                mainHandler.postDelayed({ fetchSemesterOptions() }, SEMESTER_RETRY_DELAY_MS)
                return@evaluateJavascript
            }

            when (payload.optString("status")) {
                "ok" -> {
                    val optionsArray = payload.optJSONArray("options")
                    if (optionsArray == null || optionsArray.length() == 0) {
                        semesterSpinner.isEnabled = false
                        showSemesterStatus(R.string.marks_semester_unavailable)
                        return@evaluateJavascript
                    }

                    val options = buildList {
                        for (i in 0 until optionsArray.length()) {
                            val option = optionsArray.optJSONObject(i) ?: continue
                            val label = option.optString("label").trim()
                            val value = option.optString("value").trim()
                            if (label.isNotBlank()) {
                                add(SemesterOption(label = label, value = value))
                            }
                        }
                    }
                    if (options.isEmpty()) {
                        semesterSpinner.isEnabled = false
                        showSemesterStatus(R.string.marks_semester_unavailable)
                        return@evaluateJavascript
                    }

                    val selectedValue = payload.optString("selectedValue").trim().ifEmpty { null }
                    showSemesterOptions(options, selectedValue)
                    fetchMarksRows()
                }

                "prelogin" -> redirectToLogin()
                else -> {
                    semesterSpinner.isEnabled = false
                    showSemesterStatus(R.string.marks_semester_unavailable)
                    mainHandler.postDelayed({ ensureMarksPageReady() }, SEMESTER_RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun fetchMarksRows() {
        if (marksFetchInProgress) {
            return
        }
        marksFetchInProgress = true
        webView.evaluateJavascript(EXTRACT_MARKS_ROWS_SCRIPT) { rawResult ->
            marksFetchInProgress = false
            val parsed = parseJsResult(rawResult, lowercase = false)
            if (parsed.isBlank()) {
                renderMarks(emptyList(), getString(R.string.marks_summary_default))
                return@evaluateJavascript
            }
            val payload = runCatching { JSONObject(parsed) }.getOrNull()
            if (payload == null) {
                renderMarks(emptyList(), getString(R.string.marks_summary_default))
                return@evaluateJavascript
            }

            when (payload.optString("status")) {
                "ok" -> {
                    val entriesArray = payload.optJSONArray("entries")
                    val rows = buildList {
                        if (entriesArray != null) {
                            for (i in 0 until entriesArray.length()) {
                                val row = entriesArray.optJSONObject(i) ?: continue
                                add(
                                    RawMarkRow(
                                        courseCode = row.optString("courseCode"),
                                        courseTitle = row.optString("courseTitle"),
                                        courseType = row.optString("courseType"),
                                        grade = row.optString("grade"),
                                        credits = row.optString("credits"),
                                        marks = row.optString("marks"),
                                        assessmentTitle = row.optString("assessmentTitle"),
                                        maxMark = row.optString("maxMark"),
                                        weightagePercent = row.optString("weightagePercent"),
                                        status = row.optString("status"),
                                        scoredMark = row.optString("scoredMark"),
                                        weightageMark = row.optString("weightageMark"),
                                        extra = row.optString("extra"),
                                    ),
                                )
                            }
                        }
                    }
                    val marks = groupRowsIntoSubjects(rows)
                    val summary = payload.optString("summary").takeIf { it.isNotBlank() }
                        ?: getString(R.string.marks_summary_default)
                    renderMarks(marks, summary)
                }

                "prelogin" -> redirectToLogin()
                else -> renderMarks(emptyList(), getString(R.string.marks_summary_default))
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

        lastAppliedSemesterValue = options.getOrNull(preferredIndex)?.value
        showSemesterStatus(null)
    }

    private fun applySemesterSelection(option: SemesterOption) {
        val script = buildApplySemesterScript(option)
        webView.evaluateJavascript(script) { rawResult ->
            when (parseJsResult(rawResult)) {
                APPLY_STATE_CHANGED,
                APPLY_STATE_SUBMITTED,
                APPLY_STATE_DONE
                -> {
                    lastAppliedSemesterValue = option.value
                    marksNavigationAttempts = 0
                    showSemesterStatus(R.string.marks_semester_loading)
                    renderMarks(emptyList(), getString(R.string.marks_summary_default))
                    mainHandler.postDelayed({ fetchSemesterOptions() }, SEMESTER_RETRY_DELAY_MS)
                }

                JS_STATE_PRELOGIN -> redirectToLogin()
                else -> Toast.makeText(this, R.string.marks_semester_apply_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun groupRowsIntoSubjects(rows: List<RawMarkRow>): List<MarkEntry> {
        if (rows.isEmpty()) {
            return emptyList()
        }
        val normalizedRows = mutableListOf<RawMarkRow>()
        var previousCourseCode = ""
        var previousCourseTitle = ""
        var previousCourseType = ""
        rows.forEach { row ->
            val resolvedCourseCode = row.courseCode.trim().ifBlank { previousCourseCode }
            val resolvedCourseTitle = row.courseTitle.trim().ifBlank { previousCourseTitle }
            val resolvedCourseType = row.courseType.trim().ifBlank { previousCourseType }
            if (resolvedCourseCode.isNotBlank()) {
                previousCourseCode = resolvedCourseCode
            }
            if (resolvedCourseTitle.isNotBlank()) {
                previousCourseTitle = resolvedCourseTitle
            }
            if (resolvedCourseType.isNotBlank()) {
                previousCourseType = resolvedCourseType
            }
            normalizedRows.add(
                row.copy(
                    courseCode = resolvedCourseCode,
                    courseTitle = resolvedCourseTitle,
                    courseType = resolvedCourseType,
                ),
            )
        }

        val grouped = linkedMapOf<String, MutableList<RawMarkRow>>()
        normalizedRows.forEach { row ->
            val key = buildString {
                append(row.courseCode.trim().lowercase())
                append("|")
                append(row.courseTitle.trim().lowercase())
                append("|")
                append(row.courseType.trim().lowercase())
            }
            grouped.getOrPut(key) { mutableListOf() }.add(row)
        }

        return grouped.values.map { subjectRows ->
            val assessments = subjectRows.mapIndexed { index, row ->
                buildAssessmentRow(row, index, subjectRows.size)
            }

            MarkEntry(
                courseCode = firstNonBlank(subjectRows) { it.courseCode },
                courseTitle = firstNonBlank(subjectRows) { it.courseTitle },
                courseType = firstNonBlank(subjectRows) { it.courseType },
                grade = firstNonBlank(subjectRows) { it.grade },
                credits = firstNonBlank(subjectRows) { it.credits },
                marks = firstNonBlank(subjectRows) { it.marks },
                extra = firstNonBlank(subjectRows) { it.extra },
                assessments = assessments.ifEmpty {
                    listOf(
                        AssessmentRow(
                            title = getString(R.string.marks_assessment_default),
                            maxMark = "",
                            weightagePercent = "",
                            status = "",
                            scoredMark = "",
                            weightageMark = "",
                        ),
                    )
                },
            )
        }
    }

    private fun buildAssessmentRow(row: RawMarkRow, index: Int, totalRows: Int): AssessmentRow {
        val title = row.assessmentTitle.trim().ifBlank {
            if (totalRows > 1) {
                getString(R.string.marks_assessment_indexed, index + 1)
            } else {
                getString(R.string.marks_assessment_default)
            }
        }
        return AssessmentRow(
            title = title,
            maxMark = row.maxMark.trim(),
            weightagePercent = row.weightagePercent.trim(),
            status = row.status.trim().ifBlank { row.grade.trim() },
            scoredMark = row.scoredMark.trim().ifBlank { row.marks.trim() },
            weightageMark = row.weightageMark.trim().ifBlank { row.marks.trim() },
        )
    }

    private fun firstNonBlank(
        rows: List<RawMarkRow>,
        selector: (RawMarkRow) -> String,
    ): String {
        return rows
            .asSequence()
            .map(selector)
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun showSemesterStatus(messageResId: Int?) {
        if (messageResId == null) {
            semesterStatusText.isVisible = false
            return
        }
        semesterStatusText.isVisible = true
        semesterStatusText.text = getString(messageResId)
    }

    private fun renderMarks(entries: List<MarkEntry>, summary: String) {
        marksSummaryText.text = summary
        marksSummaryText.isVisible =
            summary.isNotBlank() && summary != getString(R.string.marks_summary_default)
        marksAdapter.submit(entries)
        marksList.isVisible = entries.isNotEmpty()
        marksEmptyText.isVisible = entries.isEmpty()
        if (entries.isEmpty()) {
            marksEmptyText.text = getString(R.string.marks_no_data)
        }
    }

    private fun recoverFromNotFoundPage() {
        marksNavigationInProgress = false
        if (marksNavigationAttempts >= MAX_NAVIGATION_ATTEMPTS) {
            semesterSpinner.isEnabled = false
            showSemesterStatus(R.string.marks_semester_unavailable)
            Toast.makeText(this, R.string.destination_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        mainHandler.postDelayed(
            { webView.loadUrl(HOME_URL) },
            NAVIGATION_RETRY_DELAY_MS,
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

    private fun buildMarksNavigationScript(attemptNumber: Int): String {
        val routeTokensLiteral = toJsArrayLiteral(MARKS_ROUTE_TOKENS)
        val fallbackPathsLiteral = toJsArrayLiteral(MARKS_FALLBACK_PATHS, lowercase = false)
        val menuIdsLiteral = toJsArrayLiteral(MARKS_MENU_IDS)
        val textTermsLiteral = toJsArrayLiteral(MARKS_TEXT_TERMS)
        return """
            (function () {
              const routeTokens = $routeTokensLiteral;
              const fallbackPaths = $fallbackPathsLiteral;
              const menuIds = $menuIdsLiteral;
              const textTerms = $textTermsLiteral;
              const attemptNumber = $attemptNumber;
              const normalize = (value) => (value || '').toString().toLowerCase().trim();
              const normalizePath = (value) => normalize(value).replace(/^\/+/, '');
              const notFoundSignals = [
                'http status 404',
                '404 not found',
                'page not found',
                'error 404',
                'resource not found',
              ];

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

              const hasMarksLikeSelect = (doc) => {
                return Array.from(doc.querySelectorAll('select')).some(select => {
                  const id = normalize(select.id || '');
                  const name = normalize(select.name || '');
                  const options = Array.from(select.options || []);
                  return (id.includes('sem') || name.includes('sem')) && options.length > 1;
                });
              };
              const hasMarksPath = (doc) => {
                const path = normalizePath(
                  ((doc.location && doc.location.pathname) || '') +
                  ((doc.location && doc.location.search) || '')
                );
                return routeTokens.some(token => path.includes(token));
              };
              const currentPath = normalizePath(window.location.pathname + window.location.search);
              if (routeTokens.some(token => currentPath.includes(token)) || docs.some(doc => hasMarksPath(doc) || hasMarksLikeSelect(doc))) {
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

              let best = null;
              let bestDoc = null;
              let bestScore = -1;
              docs.forEach(doc => {
                Array.from(doc.querySelectorAll('select')).forEach(select => {
                  const id = normalize(select.id || '');
                  const name = normalize(select.name || '');
                  const options = Array.from(select.options || []);
                  let score = 0;
                  if (id.includes('sem') || name.includes('sem')) score += 8;
                  if (options.length > 1) score += 2;
                  if (options.some(opt => normalize(opt.textContent).includes('sem'))) score += 4;
                  if (score > bestScore) {
                    best = select;
                    bestDoc = doc;
                    bestScore = score;
                  }
                });
              });

              if (!best || !bestDoc) {
                return 'select_not_found';
              }

              const options = Array.from(best.options || []);
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

              best.value = targetOption.value;
              targetOption.selected = true;
              best.dispatchEvent(new Event('input', { bubbles: true }));
              best.dispatchEvent(new Event('change', { bubbles: true }));

              const refreshHosts = [bestDoc.defaultView, window, window.parent];
              for (let i = 0; i < refreshHosts.length; i++) {
                try {
                  const host = refreshHosts[i];
                  if (host && typeof host.doViewExamMark === 'function') {
                    host.doViewExamMark();
                    return 'submitted';
                  }
                } catch (_) {
                  // Ignore cross-origin access issues.
                }
              }

              const submitNode = Array.from(bestDoc.querySelectorAll('button,input[type="submit"],a[onclick],a[href]')).find(node => {
                const text = normalize(node.innerText || node.textContent || node.value || '');
                return text.includes('search') || text.includes('show') || text.includes('view') || text.includes('submit') || text.includes('go');
              });
              if (submitNode && typeof submitNode.click === 'function') {
                submitNode.click();
                return 'submitted';
              }

              const form = best.form || best.closest('form');
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

    private fun formatValue(value: String): String {
        return value.ifBlank { getString(R.string.marks_value_dash) }
    }

    private fun subjectKey(item: MarkEntry): String {
        return listOf(
            item.courseCode.trim().lowercase(),
            item.courseTitle.trim().lowercase(),
            item.courseType.trim().lowercase(),
        ).joinToString("|")
    }

    private fun abbreviateCourseType(rawType: String): String {
        val normalized = rawType.trim().lowercase()
        if (normalized.isBlank()) {
            return ""
        }
        return when {
            normalized.contains("soft skill") -> "SS"
            normalized.contains("theory") -> "TH"
            normalized.contains("lab") -> "LO"
            normalized.contains("project") -> "PJ"
            rawType.trim().length <= 4 -> rawType.trim().uppercase()
            else -> rawType.trim()
        }
    }

    private fun buildSubjectTitle(item: MarkEntry): String {
        val parts = buildList {
            item.courseCode.trim().takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
            item.courseTitle.trim().takeIf { it.isNotBlank() }?.let { add(it) }
            abbreviateCourseType(item.courseType).takeIf { it.isNotBlank() }?.let { add(it) }
        }
        return if (parts.isEmpty()) getString(R.string.marks_value_na) else parts.joinToString(" - ")
    }

    private inner class MarksAdapter : RecyclerView.Adapter<MarksViewHolder>() {
        private val items = mutableListOf<MarkEntry>()
        private val expandedKeys = mutableSetOf<String>()

        fun submit(newItems: List<MarkEntry>) {
            val incomingKeys = newItems.map { subjectKey(it) }.toSet()
            expandedKeys.retainAll(incomingKeys)
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MarksViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_mark_entry, parent, false)
            return MarksViewHolder(view as ViewGroup)
        }

        override fun onBindViewHolder(holder: MarksViewHolder, position: Int) {
            val item = items[position]
            val key = subjectKey(item)
            holder.bind(
                item = item,
                expanded = expandedKeys.contains(key),
            ) {
                if (expandedKeys.contains(key)) {
                    expandedKeys.remove(key)
                } else {
                    expandedKeys.add(key)
                }
                notifyDataSetChanged()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class MarksViewHolder(root: ViewGroup) : RecyclerView.ViewHolder(root) {
        private val headerRow: View = root.findViewById(R.id.markHeaderRow)
        private val subjectTitleText: TextView = root.findViewById(R.id.markSubjectTitleText)
        private val expandIcon: ImageView = root.findViewById(R.id.markExpandIcon)
        private val divider: View = root.findViewById(R.id.markDivider)
        private val detailsContainer: LinearLayout = root.findViewById(R.id.markDetailsContainer)

        fun bind(item: MarkEntry, expanded: Boolean, onToggle: () -> Unit) {
            subjectTitleText.text = buildSubjectTitle(item)
            expandIcon.setImageResource(
                if (expanded) R.drawable.ic_mark_expand_less else R.drawable.ic_mark_expand_more,
            )
            divider.isVisible = expanded
            detailsContainer.isVisible = expanded

            if (expanded) {
                bindAssessmentCards(item.assessments)
            } else {
                detailsContainer.removeAllViews()
            }

            headerRow.setOnClickListener { onToggle() }
        }

        private fun bindAssessmentCards(assessments: List<AssessmentRow>) {
            detailsContainer.removeAllViews()
            val inflater = LayoutInflater.from(itemView.context)
            assessments.forEach { assessment ->
                val view = inflater.inflate(R.layout.item_mark_assessment, detailsContainer, false)
                view.findViewById<TextView>(R.id.assessmentTitleText).text = assessment.title
                view.findViewById<TextView>(R.id.assessmentMaxMarkValue).text = formatValue(assessment.maxMark)
                view.findViewById<TextView>(R.id.assessmentWeightagePercentValue).text =
                    formatValue(assessment.weightagePercent)
                view.findViewById<TextView>(R.id.assessmentStatusValue).text = formatValue(assessment.status)
                view.findViewById<TextView>(R.id.assessmentScoredMarkValue).text =
                    formatValue(assessment.scoredMark)
                view.findViewById<TextView>(R.id.assessmentWeightageMarkValue).text =
                    formatValue(assessment.weightageMark)
                detailsContainer.addView(view)
            }
        }
    }

    private data class SemesterOption(
        val label: String,
        val value: String,
    )

    private data class MarkEntry(
        val courseCode: String,
        val courseTitle: String,
        val courseType: String,
        val grade: String,
        val credits: String,
        val marks: String,
        val extra: String,
        val assessments: List<AssessmentRow>,
    )

    private data class AssessmentRow(
        val title: String,
        val maxMark: String,
        val weightagePercent: String,
        val status: String,
        val scoredMark: String,
        val weightageMark: String,
    )

    private data class RawMarkRow(
        val courseCode: String,
        val courseTitle: String,
        val courseType: String,
        val grade: String,
        val credits: String,
        val marks: String,
        val assessmentTitle: String,
        val maxMark: String,
        val weightagePercent: String,
        val status: String,
        val scoredMark: String,
        val weightageMark: String,
        val extra: String,
    )

    private companion object {
        private const val HOME_URL = "https://vtop.vit.ac.in/vtop/content"

        private val MARKS_ROUTE_TOKENS = listOf(
            "examinations/studentmarkview",
            "examinations/studentmark",
            "examinations/markview",
            "examinations/examgradeview/studentgradeview",
            "examinations/examgradeview/studentgradehistory",
            "academics/common/studentmarkview",
            "academics/common/studentmark",
        )
        private val MARKS_FALLBACK_PATHS = listOf(
            "examinations/StudentMarkView",
            "examinations/studentmarkview",
            "examinations/examGradeView/StudentGradeView",
            "examinations/examGradeView/StudentGradeHistory",
            "academics/common/studentmarkview",
        )
        private val MARKS_MENU_IDS = listOf("EXM0011")
        private val MARKS_TEXT_TERMS = listOf(
            "marks",
            "mark view",
            "grade",
            "grade view",
            "grade history",
            "exam result",
        )

        private const val JS_STATE_DONE = "done"
        private const val JS_STATE_PRELOGIN = "prelogin"
        private const val JS_STATE_NOT_FOUND_PAGE = "not_found_page"
        private const val APPLY_STATE_CHANGED = "changed"
        private const val APPLY_STATE_SUBMITTED = "submitted"
        private const val APPLY_STATE_DONE = "done"

        private const val MAX_NAVIGATION_ATTEMPTS = 12
        private const val NAVIGATION_RETRY_DELAY_MS = 700L
        private const val SEMESTER_RETRY_DELAY_MS = 900L

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

        private const val EXTRACT_SEMESTERS_SCRIPT =
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

              let bestSelect = null;
              let bestScore = -1;
              docs.forEach(doc => {
                Array.from(doc.querySelectorAll('select')).forEach(select => {
                  const id = normalizeLower(select.id || '');
                  const name = normalizeLower(select.name || '');
                  const options = Array.from(select.options || []);
                  let score = 0;
                  if (id.includes('sem') || name.includes('sem')) score += 8;
                  if (options.length > 1) score += 2;
                  if (options.some(opt => normalizeLower(opt.textContent).includes('sem'))) score += 4;
                  if (score > bestScore) {
                    bestSelect = select;
                    bestScore = score;
                  }
                });
              });

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

              const selected = options.find(option => option.selected && option.value.length > 0)
                || options.find(option => option.value === normalize(bestSelect.value))
                || options[0]
                || null;
              return JSON.stringify({
                status: 'ok',
                selectedValue: selected ? selected.value : '',
                options: options,
              });
            })();
            """

        private const val EXTRACT_MARKS_ROWS_SCRIPT =
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

              const buildSummary = () => {
                const pageText = docs
                  .map(doc => normalize(doc.body ? (doc.body.innerText || doc.body.textContent) : ''))
                  .join(' ')
                  .toLowerCase();
                const sgpaMatch = pageText.match(/sgpa[^0-9]*([0-9]+(?:\.[0-9]+)?)/i);
                const cgpaMatch = pageText.match(/cgpa[^0-9]*([0-9]+(?:\.[0-9]+)?)/i);
                const summaryParts = [];
                if (sgpaMatch && sgpaMatch[1]) {
                  summaryParts.push('SGPA: ' + sgpaMatch[1]);
                }
                if (cgpaMatch && cgpaMatch[1]) {
                  summaryParts.push('CGPA: ' + cgpaMatch[1]);
                }
                return summaryParts.length > 0 ? summaryParts.join(' | ') : '';
              };

              const getCells = (row) => {
                return Array.from((row && row.children) || [])
                  .filter(cell => {
                    const tag = normalizeLower(cell.tagName || '');
                    return tag === 'td' || tag === 'th';
                  })
                  .map(cell => normalize(cell.innerText || cell.textContent));
              };

              const parseAssessmentTable = (table) => {
                if (!table) {
                  return [];
                }
                const rows = Array.from(table.querySelectorAll('tr'));
                if (rows.length < 2) {
                  return [];
                }
                const header = getCells(rows[0]).map(cell => normalizeLower(cell));
                const findHeaderIndex = (keywords) => {
                  return header.findIndex(column => keywords.some(keyword => column.includes(keyword)));
                };

                const titleIndex = findHeaderIndex(['mark title', 'assessment', 'title']);
                const maxMarkIndex = findHeaderIndex(['max. mark', 'max mark', 'maximum mark']);
                const weightagePercentIndex = findHeaderIndex(['weightage %', 'weightage%', 'weightage percentage']);
                const statusIndex = findHeaderIndex(['status']);
                const scoredMarkIndex = findHeaderIndex(['scored mark', 'score', 'obtained']);
                const weightageMarkIndex = findHeaderIndex(['weightage mark', 'weighted mark', 'weightage marks', 'weighted marks']);
                const remarkIndex = findHeaderIndex(['remark']);

                return rows
                  .slice(1)
                  .map(row => getCells(row))
                  .filter(cells => cells.some(cell => cell.length > 0))
                  .map(cells => {
                    const read = (idx, fallbackIdx) => {
                      if (idx >= 0 && idx < cells.length) {
                        return cells[idx];
                      }
                      if (fallbackIdx >= 0 && fallbackIdx < cells.length) {
                        return cells[fallbackIdx];
                      }
                      return '';
                    };
                    return {
                      assessmentTitle: read(titleIndex, 1),
                      maxMark: read(maxMarkIndex, 2),
                      weightagePercent: read(weightagePercentIndex, 3),
                      status: read(statusIndex, 4),
                      scoredMark: read(scoredMarkIndex, 5),
                      weightageMark: read(weightageMarkIndex, 6),
                      remark: read(remarkIndex, 7),
                    };
                  })
                  .filter(row => {
                    const title = normalizeLower(row.assessmentTitle);
                    if (title.includes('total') || title.includes('minimum marks required')) {
                      return false;
                    }
                    return row.assessmentTitle || row.maxMark || row.scoredMark || row.weightageMark;
                  });
              };

              const extractFromCustomLayout = (doc) => {
                const extracted = [];
                const mainTables = Array.from(doc.querySelectorAll('table.customTable'));
                mainTables.forEach(mainTable => {
                  const rows = Array.from(mainTable.querySelectorAll('tr'));
                  for (let i = 0; i < rows.length; i++) {
                    const row = rows[i];
                    const className = normalizeLower(row.className || '');
                    if (!className.includes('tablecontent') || className.includes('level1')) {
                      continue;
                    }
                    if (row.querySelector('table.customTable-level1')) {
                      continue;
                    }
                    const cells = getCells(row);
                    if (cells.length < 5) {
                      continue;
                    }
                    const headerLike = cells.some(cell => {
                      const text = normalizeLower(cell);
                      return text.includes('course code') || text.includes('course title');
                    });
                    if (headerLike) {
                      continue;
                    }

                    const courseCode = cells[2] || '';
                    const courseTitle = cells[3] || '';
                    const courseType = cells[4] || '';
                    if (!courseCode && !courseTitle) {
                      continue;
                    }

                    let nestedTable = null;
                    for (let j = i + 1; j < rows.length; j++) {
                      const nextRow = rows[j];
                      const nextClass = normalizeLower(nextRow.className || '');
                      if (nextClass.includes('tablecontent') && !nextClass.includes('level1')) {
                        nestedTable = nextRow.querySelector('table.customTable-level1');
                        if (nestedTable) {
                          i = j;
                        }
                        break;
                      }
                    }

                    const assessments = parseAssessmentTable(nestedTable);
                    if (assessments.length === 0) {
                      extracted.push({
                        courseCode: courseCode,
                        courseTitle: courseTitle,
                        courseType: courseType,
                        assessmentTitle: '',
                        maxMark: '',
                        weightagePercent: '',
                        status: '',
                        scoredMark: '',
                        weightageMark: '',
                        grade: '',
                        credits: '',
                        marks: '',
                        extra: '',
                      });
                      continue;
                    }

                    assessments.forEach(assessment => {
                      extracted.push({
                        courseCode: courseCode,
                        courseTitle: courseTitle,
                        courseType: courseType,
                        assessmentTitle: assessment.assessmentTitle,
                        maxMark: assessment.maxMark,
                        weightagePercent: assessment.weightagePercent,
                        status: assessment.status,
                        scoredMark: assessment.scoredMark,
                        weightageMark: assessment.weightageMark,
                        grade: '',
                        credits: '',
                        marks: assessment.scoredMark,
                        extra: assessment.remark,
                      });
                    });
                  }
                });
                return extracted;
              };

              const customEntries = [];
              docs.forEach(doc => {
                Array.prototype.push.apply(customEntries, extractFromCustomLayout(doc));
              });

              if (customEntries.length > 0) {
                return JSON.stringify({
                  status: 'ok',
                  summary: buildSummary(),
                  entries: customEntries,
                });
              }

              const candidates = [];
              docs.forEach(doc => {
                Array.from(doc.querySelectorAll('table')).forEach(table => {
                  const rows = Array.from(table.querySelectorAll('tr')).map(row =>
                    Array.from(row.querySelectorAll('th,td')).map(cell => normalize(cell.innerText || cell.textContent))
                  );
                  const nonEmptyRows = rows.filter(row => row.some(cell => cell.length > 0));
                  if (nonEmptyRows.length < 2) {
                    return;
                  }
                  const headerRow = nonEmptyRows[0].map(cell => normalizeLower(cell));
                  const headerText = headerRow.join(' ');
                  let score = 0;
                  if (headerText.includes('course')) score += 8;
                  if (headerText.includes('code')) score += 5;
                  if (headerText.includes('grade')) score += 7;
                  if (headerText.includes('credit')) score += 5;
                  if (headerText.includes('mark')) score += 5;
                  score += Math.min(nonEmptyRows.length, 20);
                  candidates.push({ rows: nonEmptyRows, header: headerRow, score: score });
                });
              });

              if (candidates.length === 0) {
                return JSON.stringify({ status: 'not_found' });
              }

              candidates.sort((a, b) => b.score - a.score);
              const best = candidates[0];
              const header = best.header;
              const rows = best.rows;

              const findHeaderIndex = (keywords) => {
                return header.findIndex(column => keywords.some(keyword => column.includes(keyword)));
              };

              const codeIndex = findHeaderIndex(['course code', 'coursecode', 'code']);
              const titleIndex = findHeaderIndex(['course title', 'course name', 'title', 'subject']);
              const courseTypeIndex = findHeaderIndex(['course type', 'class type', 'type']);
              const assessmentTitleIndex = findHeaderIndex(['assessment', 'component', 'test', 'exam', 'cat', 'quiz']);
              const maxMarkIndex = findHeaderIndex(['max. mark', 'max mark', 'maximum mark']);
              const weightagePercentIndex = findHeaderIndex(['weightage %', 'weightage%', 'weightage percentage']);
              const statusIndex = findHeaderIndex(['status']);
              const scoredMarkIndex = findHeaderIndex(['scored mark', 'mark scored', 'obtained mark', 'obtained', 'score']);
              const weightageMarkIndex = findHeaderIndex(['weightage mark', 'weighted mark', 'weightage marks', 'weighted marks']);
              const gradeIndex = findHeaderIndex(['grade']);
              const creditsIndex = findHeaderIndex(['credit']);
              const marksIndex = findHeaderIndex(['mark', 'score']);
              const shouldSkipHeader = header.some(col => col.includes('course') || col.includes('grade') || col.includes('credit'));

              const entryRows = shouldSkipHeader ? rows.slice(1) : rows;
              const parsedEntries = entryRows
                .map(cells => cells.map(cell => normalize(cell)))
                .filter(cells => cells.some(cell => cell.length > 0))
                .map(cells => {
                  const read = (idx, fallbackIdx) => {
                    if (idx >= 0 && idx < cells.length) {
                      return cells[idx];
                    }
                    if (fallbackIdx >= 0 && fallbackIdx < cells.length) {
                      return cells[fallbackIdx];
                    }
                    return '';
                  };
                  const courseCode = read(codeIndex, 0);
                  const courseTitle = read(titleIndex, 1);
                  const courseType = read(courseTypeIndex, -1);
                  const assessmentTitle = read(assessmentTitleIndex, -1);
                  const maxMark = read(maxMarkIndex, -1);
                  const weightagePercent = read(weightagePercentIndex, -1);
                  const status = read(statusIndex, -1);
                  const scoredMark = read(scoredMarkIndex, marksIndex);
                  const weightageMark = read(weightageMarkIndex, -1);
                  const grade = read(gradeIndex, -1);
                  const credits = read(creditsIndex, -1);
                  const marks = read(marksIndex, -1);
                  const extra = cells.join(' | ');
                  return {
                    courseCode,
                    courseTitle,
                    courseType,
                    assessmentTitle,
                    maxMark,
                    weightagePercent,
                    status,
                    scoredMark,
                    weightageMark,
                    grade,
                    credits,
                    marks,
                    extra
                  };
                })
                .filter(row => row.courseCode || row.courseTitle || row.assessmentTitle || row.grade || row.marks);

              const entries = [];
              let previousCourseCode = '';
              let previousCourseTitle = '';
              let previousCourseType = '';
              parsedEntries.forEach(row => {
                const normalizedRow = { ...row };
                if (!normalizedRow.courseCode && previousCourseCode) {
                  normalizedRow.courseCode = previousCourseCode;
                }
                if (!normalizedRow.courseTitle && previousCourseTitle) {
                  normalizedRow.courseTitle = previousCourseTitle;
                }
                if (!normalizedRow.courseType && previousCourseType) {
                  normalizedRow.courseType = previousCourseType;
                }
                if (normalizedRow.courseCode) {
                  previousCourseCode = normalizedRow.courseCode;
                }
                if (normalizedRow.courseTitle) {
                  previousCourseTitle = normalizedRow.courseTitle;
                }
                if (normalizedRow.courseType) {
                  previousCourseType = normalizedRow.courseType;
                }
                entries.push(normalizedRow);
              });

              const summary = buildSummary();

              return JSON.stringify({
                status: 'ok',
                summary: summary,
                entries: entries,
              });
            })();
            """
    }
}
