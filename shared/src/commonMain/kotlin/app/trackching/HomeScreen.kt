package app.trackching

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TextButton

/** Temporary Home: each account and its balance. The real Home is P1-18. */
@Composable
fun HomeScreen(ledger: Ledger) {
    val c = TrackchingTheme.colors
    val balances by remember(ledger) { ledger.balances() }.collectAsState(initial = emptyList())
    var addingAccount by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Tag("Accounts") }
            TextButton(onClick = { managing = true }) {
                Text("MANAGE", color = c.orangeInk, fontWeight = FontWeight.Bold)
            }
        }
        balances.forEach { account ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).background(Color(account.color)))
                Text(account.name, Modifier.weight(1f), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Amount(
                    account.balance,
                    if (account.balance < 0) AmountKind.Expense else AmountKind.Transfer,
                    maxFontSize = 20.sp,
                )
            }
        }
        OffsetBlock(Modifier.fillMaxWidth().clickable(role = Role.Button) { addingAccount = true }) {
            Text(
                "+ ADD ACCOUNT",
                Modifier.padding(12.dp),
                color = c.onOrange,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }

    if (addingAccount) {
        AccountSheet(ledger, onDismiss = { addingAccount = false })
    }
    if (managing) {
        ManageAccountsSheet(ledger, onDismiss = { managing = false })
    }
}