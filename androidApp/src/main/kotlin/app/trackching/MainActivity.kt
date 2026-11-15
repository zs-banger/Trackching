package app.trackching

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    /** Where a tapped reminder asks to open (P3-11). Cleared once the app has gone there. */
    private var opening by mutableStateOf<ReminderTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = createDatabase(applicationContext)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        // A rotation doesn't open it again.
        if (savedInstanceState == null) opening = targetOf(intent)
        // Keeps the daily check going, e.g. after a restore brought the switches back on.
        if (Ledger(database).anyReminderOn()) scheduleReminders(applicationContext, true)
        setContent {
            AppShell(database, version, opening = opening, onOpened = {
                opening = null
                intent.removeExtra(OpenExtra)
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opening = targetOf(intent)
    }

    private fun targetOf(intent: Intent?): ReminderTarget? =
        intent?.getStringExtra(OpenExtra)?.let { name -> ReminderTarget.entries.firstOrNull { it.name == name } }
}
