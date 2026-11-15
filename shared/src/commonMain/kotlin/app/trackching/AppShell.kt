package app.trackching

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.runtime.key
import app.trackching.db.TrackchingDatabase
import app.trackching.db.Txn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import trackching.shared.generated.resources.Res
import trackching.shared.generated.resources.ic_categories
import trackching.shared.generated.resources.ic_events
import trackching.shared.generated.resources.ic_home
import trackching.shared.generated.resources.ic_ious
import trackching.shared.generated.resources.ic_transactions
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The 5 bottom tabs. */
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
    Ious("IOUs", Res.drawable.ic_ious, "New IOU"),
}

/** The app: setup on first launch, the tabs after that. */
@Composable
fun AppShell(database: TrackchingDatabase, version: String = "") {
    val ledger = remember(database) { Ledger(database) }
    var setupDone by remember(ledger) { mutableStateOf(ledger.setupDone()) }
    var theme by remember(ledger) { mutableStateOf(ledger.theme()) }
    var generation by remember { mutableStateOf(0) }
    var justRestored by remember { mutableStateOf(false) }
    TrackchingTheme(theme) {
        if (setupDone) {
            key(generation) {
                MainShell(
                    ledger,
                    version = version,
                    theme = theme,
                    onTheme = { theme = it; ledger.setTheme(it) },
                    onErased = { theme = ThemeChoice.System; justRestored = false; setupDone = false },
                    onRestored = {
                        theme = ledger.theme()
                        justRestored = true
                        setupDone = ledger.setupDone()
                        generation++
                    },
                    justRestored = justRestored,
                )
            }
        } else {
            SetupScreen(
                ledger,
                onDone = { justRestored = false; setupDone = true },
                onRestored = {
                    theme = ledger.theme()
                    justRestored = true
                    setupDone = true
                },
            )
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun MainShell(
    ledger: Ledger,
    version: String,
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    onErased: () -> Unit,
    onRestored: () -> Unit,
    justRestored: Boolean,
) {
    val c = TrackchingTheme.colors
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var pages by remember { mutableStateOf(listOf<Page>()) }
    fun back() { pages = pages.dropLast(1) }
    fun open(page: Page) { pages = pages + page }

    PlatformBackHandler(enabled = pages.isNotEmpty()) { back() }
    var hidden by remember(ledger) { mutableStateOf(ledger.amountsHidden()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Once per MainShell: right after a restore, say so.
    LaunchedEffect(Unit) {
        if (justRestored) snackbar.showSnackbar("Restored from your backup file")
    }
    var adding by remember { mutableStateOf(false) }
    var addingCategory by remember { mutableStateOf(false) }
    var addingEvent by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var editingEvent by remember { mutableStateOf<EventRow?>(null) }
    var runSheet by remember { mutableStateOf<RunSheetFor?>(null) } // new or changed dates of an occasional event (P3-19, P3-20)
    var managingAccounts by remember { mutableStateOf(false) }
    var checkingIn by remember { mutableStateOf(false) }
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val dueCount by remember(ledger) { ledger.dueCount(today) }.collectAsState(initial = 0)
    val overdue by remember(ledger) { ledger.overdueCount(today) }.collectAsState(initial = 0)
    var newIou by remember { mutableStateOf<IouKind?>(null) }   // New IOU, starting on this kind (P3-8)
    var editingIou by remember { mutableStateOf<Long?>(null) }  // Edit IOU: its id (P3-8)
    var payingIou by remember { mutableStateOf<Long?>(null) }   // Record payment / Payment received / Hand back (P3-9)
    // Once per app start: anything due opens the check-in.
    LaunchedEffect(Unit) {
        if (ledger.dueRepeats(today).isNotEmpty() || ledger.dueInstallments(today).isNotEmpty() || ledger.dueCardPayments(today).isNotEmpty()) {
            checkingIn = true
        }
    }

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
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (tab == Tab.Home && pages.isEmpty()) {
                        TextButton(onClick = { open(Page.Settings) }) {
                            Text("⚙", color = c.ink, fontSize = 22.sp)
                        }
                    } else {
                        Spacer(Modifier)
                    }
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
                            icon = {
                                BadgedBox(badge = {
                                    if (t == Tab.Transactions && dueCount > 0) Badge { Text("$dueCount") }
                                    if (t == Tab.Ious && overdue > 0) Badge { Text("$overdue") }
                                }) {
                                    Icon(painterResource(t.icon), contentDescription = null)
                                }
                            },
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
                            Tab.Ious -> newIou = IouKind.OWE
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
                            waiting = dueCount,
                            onCheckIn = { checkingIn = true },
                        )

                        Tab.Events -> EventsScreen(ledger, onNewEvent = { addingEvent = true },
                            onOpenEvent = { open(Page.EventDetail(it, "Events")) })
                        Tab.Categories -> CategoriesScreen(ledger, onLog = { adding = true })
                        Tab.Ious -> IousScreen(
                            ledger,
                            today = today,
                            onOpenPerson = { line, kind -> open(Page.PersonIous(line.personId, line.person, kind)) },
                            onOpenDueSoon = { open(Page.DueSoon) },
                            onNew = { newIou = it },
                            onOpenIou = { open(Page.IouDetail(it, "IOUs")) },
                        )
                    }
                    Page.Accounts -> AccountsScreen(
                        ledger,
                        onBack = ::back,
                        onOpenAccount = { open(Page.AccountHistory(it.id, it.name)) },
                    )
                    is Page.PersonIous -> PersonIousScreen(
                        ledger, today, page.personId, page.name, page.kind, onBack = ::back,
                        onOpenIou = { open(Page.IouDetail(it, page.name)) },
                    )
                    Page.DueSoon -> DueSoonScreen(ledger, today, onBack = ::back, onOpenIou = { open(Page.IouDetail(it, "Due soon")) })
                    is Page.IouDetail -> IouScreen(
                        ledger,
                        iouId = page.id,
                        today = today,
                        backTo = page.from,
                        onBack = ::back,
                        onRecord = { payingIou = page.id },
                        onEdit = { editingIou = page.id },
                        onDeletedPayment = { deleted -> showUndo("Payment deleted") { ledger.restorePayment(deleted) } },
                    )
                    Page.Settings -> SettingsScreen(
                        ledger,
                        version = version,
                        theme = theme,
                        onTheme = onTheme,
                        onBack = ::back,
                        onManageAccounts = { managingAccounts = true },
                        onManageCategories = {
                            pages = emptyList()
                            tab = Tab.Categories
                        },
                        onErased = onErased,
                        onRestored = onRestored,
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
                        onNewDates = { runSheet = RunSheetFor(page.id) },
                        onChangeDates = { run -> runSheet = RunSheetFor(page.id, run) },
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
            runSheet?.let { open ->
                EventRunSheet(
                    ledger,
                    eventId = open.eventId,
                    editing = open.editing,
                    onDismiss = { runSheet = null },
                    onSaved = { changed ->
                        if (changed == null) scope.launch { snackbar.showSnackbar("Dates saved") }
                        else showUndo(runMessage("Dates changed", changed)) { ledger.undoRun(changed) }
                    },
                    onDeleted = { deleted -> showUndo(runMessage("Dates deleted", deleted)) { ledger.undoRun(deleted) } },
                )
            }
            newIou?.let { kind ->
                IouSheet(
                    ledger,
                    startKind = kind,
                    onDismiss = { newIou = null },
                    onSaved = { scope.launch { snackbar.showSnackbar("IOU saved") } },
                )
            }
            payingIou?.let { id ->
                PaymentSheet(
                    ledger,
                    iouId = id,
                    onDismiss = { payingIou = null },
                    onSaved = { paymentId ->
                        showUndo("Payment saved") { ledger.deletePayment(paymentId) }
                    },
                )
            }
            editingIou?.let { id ->
                IouSheet(
                    ledger,
                    editing = id,
                    onDismiss = { editingIou = null },
                    onSaved = { scope.launch { snackbar.showSnackbar("IOU saved") } },
                )
            }
            if (managingAccounts) {
                ManageAccountsSheet(ledger, onDismiss = { managingAccounts = false })
            }
            if (checkingIn) {
                CheckInSheet(ledger, onDismiss = { checkingIn = false })
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

/** "Dates deleted · 2 transactions untagged", or just "Dates deleted" when nothing was. */
internal fun runMessage(what: String, undo: RunUndo): String =
    if (undo.untagged.isEmpty()) what else "$what · ${plural(undo.untagged.size.toLong(), "transaction")} untagged"
