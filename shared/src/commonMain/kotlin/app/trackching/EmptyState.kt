package app.trackching

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What's missing, in one short grey line, and an orange button that fills it. */
@Composable
fun EmptyState(
    message: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    val c = TrackchingTheme.colors
    Column(
        modifier,
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            message,
            color = c.ink2,
            fontSize = 15.sp,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        TextButton(onClick = onAction, contentPadding = PaddingValues(vertical = 8.dp)) {
            Text("+ ${action.uppercase()}", color = c.orangeInk, fontWeight = FontWeight.Bold)
        }
    }
}

/** An EmptyState in the middle of a whole tab. */
@Composable
fun EmptyScreen(message: String, action: String, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        EmptyState(message, action, onAction, centered = true)
    }
}
