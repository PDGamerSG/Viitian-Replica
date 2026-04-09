package edu.vit.vtop.replica

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity

class DashboardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        setSupportActionBar(findViewById(R.id.topAppBar))

        findViewById<View>(R.id.cardMarks).setOnClickListener {
            openMarks()
        }
        findViewById<View>(R.id.cardAttendance).setOnClickListener {
            openPortal(PortalActivity.ACTION_ATTENDANCE)
        }
        findViewById<View>(R.id.cardDaUpload).setOnClickListener {
            openDaUpload()
        }
        findViewById<View>(R.id.cardCoursePage).setOnClickListener {
            openCoursePage()
        }
        findViewById<View>(R.id.cardPortalHome).setOnClickListener {
            openPortal(PortalActivity.ACTION_PORTAL_HOME)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.dashboard_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                logoutToLogin()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openPortal(action: String) {
        startActivity(
            Intent(this, PortalActivity::class.java)
                .putExtra(PortalActivity.EXTRA_ACTION, action),
        )
    }

    private fun openMarks() {
        startActivity(Intent(this, MarksActivity::class.java))
    }

    private fun openDaUpload() {
        startActivity(Intent(this, DaCoursesActivity::class.java))
    }

    private fun openCoursePage() {
        startActivity(Intent(this, CoursePageCoursesActivity::class.java))
    }

    private fun logoutToLogin() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
    }
}

