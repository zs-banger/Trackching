package app.trackching

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** A screen that opens on top of the tabs. The newest one is shown, and Back closes it. */
sealed interface Page {
    /** S5: total, accounts and Balance over time. */
    data object Accounts : Page

    /** Transactions for one account only, opened from Accounts. */
    data class AccountHistory(val accountId: Long, val name: String) : Page
}

/** "‹ HOME" at the top left of a page. `to` is the screen it goes back to. */
@Composable
fun BackLink(to: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onBack, modifier = modifier) {
        Text(
            "‹ ${to.uppercase()}",
            color = TrackchingTheme.colors.orangeInk,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Runs `onBack` when the phone's Back gesture or button is used, while `enabled` is true.
 * Each platform does this its own way, so the real code lives in androidMain (and later the web).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
