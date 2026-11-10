package app.trackching

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trackching.db.TrackchingDatabase
import app.trackching.db.Txn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import trackching.shared.generated.resources.Res
import trackching.shared.generated.resources.ic_categories
import trackching.shared.generated.resources.ic_events
import trackching.shared.generated.resources.ic_home
import trackching.shared.generated.resources.ic_ious
import trackching.shared.generated.resources.ic_transactions

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

/** The app: setup on first launch, the tabs after that. */
@Composable
fun AppShell(database: TrackchingDatabase) {
    val ledger = remember(database) { Ledger(database) }
    var setupDone by remember(ledger) { mutableStateOf(ledger.setupDone()) }
    if (setupDone) {
        MainShell(ledger)
    } else {
        SetupScreen(ledger, onDone = { setupDone = true })
    }
}

@Composable
private fun MainShell(ledger: Ledger) {
    val c = TrackchingTheme.colors
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var pages by remember { mutableStateOf(listOf<Page>()) }
    fun back() { pages = pages.dropLast(1) }
    fun open(page: Page) { pages = pages + page }

    PlatformBackHandler(enabled = pages.isNotEmpty()) { back() }
    var hidden by remember(ledger) { mutableStateOf(ledger.amountsHidden()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var addingCategory by remember { mutableStateOf(false) }
    var addingEvent by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var editingEvent by remember { mutableStateOf<EventRow?>(null) }

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
    CompositionLocalProvider(LocalAmountsHidden provides hidden) {
        Scaffold(
            topBar = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        hidden = !hidden
                        ledger.setAmountsHidden(hidden)
                    }) {
                        Text(if (hidden) "SHOW ₱" else "HIDE ₱", color = c.orangeInk, fontWeight = FontWeight.Bold)
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = {
                                snackbar.currentSnackbarData?.dismiss() // an Undo belongs to the tab it came from
                                tab = t
                                pages = emptyList()
                            },
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
                            Tab.Events -> addingEvent = true
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
            snackbarHost = {
                SnackbarHost(snackbar)
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (val page = pages.lastOrNull()) {
                    null -> when (tab) {
                        Tab.Home -> HomeScreen(
                            ledger,
                            onOpen = { tab = it },
                            onLog = { adding = true },
                            onNewEvent = { addingEvent = true },
                            onOpenAccounts = { open(Page.Accounts) },
                            onOpenEvent = { open(Page.EventDetail(it, "Home")) },
                        )

                        Tab.Transactions -> TransactionsScreen(
                            ledger,
                            onOpen = { id ->
                                snackbar.currentSnackbarData?.dismiss()
                                editing = ledger.get(id)
                            },
                            onLog = { adding = true },
                            onOpenReports = { open(Page.IncomeVsExpenses) },
                        )

                        Tab.Events -> EventsScreen(ledger, onNewEvent = { addingEvent = true },
                            onOpenEvent = { open(Page.EventDetail(it, "Events")) })
                        Tab.Categories -> CategoriesScreen(ledger, onLog = { adding = true })
                        else -> Text(
                            "${tab.label} is coming soon",
                            Modifier.align(Alignment.Center)
                        )
                    }
                    Page.Accounts -> AccountsScreen(
                        ledger,
                        onBack = ::back,
                        onOpenAccount = { open(Page.AccountHistory(it.id, it.name)) },
                    )
                    is Page.AccountHistory -> TransactionsScreen(
                        ledger,
                        onOpen = { id ->
                            snackbar.currentSnackbarData?.dismiss()
                            editing = ledger.get(id)
                        },
                        onLog = { adding = true },
                        account = page.accountId,
                        onBack = ::back,
                    )
                    Page.IncomeVsExpenses -> IncomeVsExpensesScreen(
                        ledger,
                        onBack = ::back,
                        onOpenPeriod = { bucket, tab -> open(Page.PeriodHistory(bucket, tab)) },
                    )
                    is Page.PeriodHistory -> TransactionsScreen(
                        ledger,
                        onOpen = { id ->
                            snackbar.currentSnackbarData?.dismiss()
                            editing = ledger.get(id)
                        },
                        onLog = { adding = true },
                        fixed = page.bucket,
                        startTab = page.tab,
                        backTo = "Income vs Expenses",
                        onBack = ::back,
                    )
                    is Page.EventDetail -> EventScreen(
                        ledger,
                        eventId = page.id,
                        backTo = page.from,
                        onBack = ::back,
                        onEdit = { editingEvent = it },
                        onOpenTransactions = { bucket, tab -> open(Page.EventHistory(page.id, bucket, tab)) },
                        onLog = { adding = true },
                    )
                    is Page.EventHistory -> TransactionsScreen(
                        ledger,
                        onOpen = { id ->
                            snackbar.currentSnackbarData?.dismiss()
                            editing = ledger.get(id)
                        },
                        onLog = { adding = true },
                        event = page.eventId,
                        fixed = page.bucket,
                        startTab = page.tab,
                        backTo = page.bucket.title,
                        onBack = ::back,
                    )
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
            if (addingEvent) {
                EventSheet(
                    ledger,
                    onDismiss = { addingEvent = false },
                    onSaved = { scope.launch { snackbar.showSnackbar("Event saved") } },
                )
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
            val eventToEdit = editingEvent
            if (eventToEdit != null) {
                EventSheet(
                    ledger,
                    editing = eventToEdit,
                    onDismiss = { editingEvent = null },
                    onSaved = { scope.launch { snackbar.showSnackbar("Event saved") } },
                    onDeleted = { deleted ->
                        // Close the deleted event's pages, then offer Undo.
                        pages = pages.filterNot {
                            (it is Page.EventDetail && it.id == deleted.event.id) ||
                                (it is Page.EventHistory && it.eventId == deleted.event.id)
                        }
                        showUndo("Event deleted") { ledger.restoreEvent(deleted) }
                    },
                )
            }
        }
    }
}
