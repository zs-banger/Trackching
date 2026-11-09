package app.trackching

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trackching.db.TrackchingDatabase
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import trackching.shared.generated.resources.Res
import trackching.shared.generated.resources.ic_categories
import trackching.shared.generated.resources.ic_events
import trackching.shared.generated.resources.ic_home
import trackching.shared.generated.resources.ic_ious
import trackching.shared.generated.resources.ic_transactions
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import app.trackching.db.Txn

/** The 5 bottom tabs. IOUs stays disabled until Phase 3. */
enum class Tab(
    val label: String,
    val icon: DrawableResource,
    val addLabel: String,
    val enabled: Boolean = true,
) {
    Home("Home", Res.drawable.ic_home, "New transaction"),
    Transactions("Transactions", Res.drawable.ic_transactions, "New transaction"),
    Events("Events", Res.drawable.ic_events, "New event"),
    Categories("Categories", Res.drawable.ic_categories, "New category"),
    Ious("IOUs", Res.drawable.ic_ious, "New IOU", enabled = false),
}

@Composable
fun AppShell(database: TrackchingDatabase) {
    val c = TrackchingTheme.colors
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val ledger = remember(database) { Ledger(database) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var addingCategory by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Txn?>(null) }

    // One place for every "… · Undo" message: shows it for 5 seconds, runs onUndo if tapped.
    fun showUndo(message: String, onUndo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = withTimeoutOrNull(5_000) {
                snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        enabled = t.enabled,
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(t.label, fontWeight = FontWeight.Bold) },
                    )
                }
            }
        },
        floatingActionButton = {
            // What it opens per tab is wired up in P1-4, P1-8, P1-11 and P1-14.
            OffsetBlock(
                Modifier.clickable(role = Role.Button) {
                    when (tab) {
                        Tab.Home, Tab.Transactions -> adding = true
                        Tab.Categories -> addingCategory = true
                        else -> {}
                    }
                },
                color = c.blue,
                edge = c.orange,
            ) {
                Text(
                    "+ ${tab.addLabel.uppercase()}",
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = c.onBlue,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar)
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Home -> HomeScreen(ledger)
                Tab.Transactions -> TransactionsScreen(ledger, onOpen = { id ->
                    snackbar.currentSnackbarData?.dismiss()
                    editing = ledger.get(id)
                })
                Tab.Categories -> CategoriesScreen(ledger)
                else -> Text("${tab.label} is coming soon", Modifier.align(Alignment.Center))
            }
        }
        if (adding) {
            TxnSheet(
                ledger = ledger,
                onDismiss = { adding = false },
                onSaved = { id -> showUndo("Saved") { ledger.undo(id) } },
            )
        }
        if (addingCategory) {
            CategorySheet(ledger, onDismiss = { addingCategory = false })
        }
        val toEdit = editing
        if (toEdit != null) {
            TxnSheet(
                ledger = ledger,
                editing = toEdit,
                onDismiss = { editing = null },
                onSaved = {},
                onDeleted = { deleted -> showUndo("Deleted") { ledger.restore(deleted) } },
            )
        }
    }
}