package app.trackching

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.trackching.db.TrackchingDatabase
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

actual val remindersSupported: Boolean = true

/** The notification's tap carries this extra: a [ReminderTarget] name. */
const val OpenExtra = "app.trackching.OPEN"

private const val ChannelId = "reminders"
private const val WorkName = "daily-reminders"

/** Whether a notification can show: allowed (Android 13+) and not turned off for the app. */
fun canPostReminders(context: Context): Boolean {
    val allowed = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return allowed && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

@Composable
actual fun rememberReminderControl(): ReminderControl {
    val context = LocalContext.current
    val waiting = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        waiting[0]?.invoke(granted && canPostReminders(context))
        waiting[0] = null
    }
    return remember(context, launcher) {
        object : ReminderControl {
            override fun ask(onResult: (Boolean) -> Unit) {
                when {
                    canPostReminders(context) -> onResult(true)
                    Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                        waiting[0] = onResult
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    else -> onResult(false) // notifications are turned off for the app in the phone's settings
                }
            }

            override fun schedule(on: Boolean) = scheduleReminders(context, on)
        }
    }
}

/**
 * Starts the once-a-day check at about 8 in the morning, or stops it.
 * WorkManager keeps it across restarts of the phone, so reminders still come after a reboot.
 */
@OptIn(ExperimentalTime::class)
fun scheduleReminders(context: Context, on: Boolean) {
    val work = WorkManager.getInstance(context.applicationContext)
    if (!on) {
        work.cancelUniqueWork(WorkName)
        return
    }
    val delay = minutesUntilMorning(Clock.System.now(), TimeZone.currentSystemDefault())
    val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
        .setInitialDelay(delay, TimeUnit.MINUTES)
        .build()
    work.enqueueUniquePeriodicWork(WorkName, ExistingPeriodicWorkPolicy.KEEP, request)
}

/** The daily check: reads what's due and posts a notification for each. */
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    @OptIn(ExperimentalTime::class)
    override fun doWork(): Result {
        // Without permission nothing is checked, so the days are still covered once it's allowed (up to a week).
        if (!canPostReminders(applicationContext)) return Result.success()
        val driver = createDriver(applicationContext)
        try {
            val ledger = Ledger(TrackchingDatabase(driver))
            if (!ledger.anyReminderOn()) return Result.success()
            postReminders(applicationContext, ledger.reminders(Clock.System.todayIn(TimeZone.currentSystemDefault())))
        } finally {
            driver.close()
        }
        return Result.success()
    }
}

/** Posts [reminders]. Tapping one opens the app on its tab (IOUs), the check-in, or Settings. */
@SuppressLint("MissingPermission") // canPostReminders checks it
fun postReminders(context: Context, reminders: List<Reminder>) {
    if (reminders.isEmpty() || !canPostReminders(context)) return
    val manager = NotificationManagerCompat.from(context)
    manager.createNotificationChannel(
        NotificationChannelCompat.Builder(ChannelId, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName("Reminders")
            .setDescription("IOUs, bills and repeats that are due, and the monthly backup")
            .build()
    )
    reminders.forEach { r ->
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return@forEach
        open.putExtra(OpenExtra, r.target.name)
        open.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val tap = PendingIntent.getActivity(
            context, r.target.ordinal, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(r.title)
            .setContentText(r.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(r.key, 0, notification)
    }
}
