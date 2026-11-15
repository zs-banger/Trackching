package app.trackching

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Bump this when the file layout changes. Older apps then refuse newer files. 2: repeat rules (P3-1). 4: IOUs (P3-5). 5: repeating events (P3-4). 6: occasional events (P3-19). 7: IOUs that paid for something (P3-8). 8: installments (P3-17). 9: credit accounts' due day and minimum (P3-12). 10: card plans (P3-15). */
const val BackupFormat = 10

@Serializable
data class BackupFile(
    val app: String = "trackching",
    val format: Int = BackupFormat,
    val createdOn: String,
    val accounts: List<AccountBackup>,
    val categories: List<CategoryBackup>,
    val events: List<EventBackup>,
    val txns: List<TxnBackup>,
    val settings: Map<String, String>,
    val repeats: List<RepeatBackup> = emptyList(),
    val people: List<PersonBackup> = emptyList(),
    val ious: List<IouBackup> = emptyList(),
    val iouPayments: List<IouPaymentBackup> = emptyList(),
    val installments: List<InstallmentBackup> = emptyList(),
    val cardPlans: List<CardPlanBackup> = emptyList(),
    val cardPayments: List<CardPaymentBackup> = emptyList(),
    val eventRuns: List<RunBackup> = emptyList(),
)

@Serializable
data class AccountBackup(
    val id: Long, val name: String, val type: String, val color: Long,
    val startingCentavos: Long, val archived: Long, val sortOrder: Long,
    val dueDay: Long? = null, val minimumCentavos: Long? = null,
)

@Serializable
data class CategoryBackup(
    val id: Long, val name: String, val kind: String, val parentId: Long?,
    val color: Long, val sortOrder: Long, val icon: String,
)

@Serializable
data class EventBackup(
    val id: Long, val name: String, val startDate: String, val endDate: String?,
    val repeatEvery: Long? = null, val repeatUnit: String? = null, val occasional: Long = 0,
)

@Serializable
data class RunBackup(val id: Long, val eventId: Long, val startDate: String, val endDate: String)

@Serializable
data class TxnBackup(
    val id: Long, val kind: String, val centavos: Long, val accountId: Long,
    val toAccountId: Long?, val categoryId: Long?, val occurredAt: Long, val eventId: Long?,
    val repeatId: Long? = null,
)

@Serializable
data class RepeatBackup(
    val id: Long, val every: Long, val unit: String, val startDate: String, val times: Long?,
    val untilDate: String?, val done: Long, val kind: String, val centavos: Long,
    val accountId: Long, val toAccountId: Long?, val categoryId: Long?,
)

@Serializable
data class PersonBackup(val id: Long, val name: String)

@Serializable
data class IouBackup(
    val id: Long, val kind: String, val personId: Long, val centavos: Long, val whatFor: String,
    val openedOn: String, val dueOn: String?, val interestKind: String, val interestValue: Long,
    val note: String, val txnId: Long?, val spendTxnId: Long? = null,
)

@Serializable
data class CardPlanBackup(val id: Long, val accountId: Long, val name: String, val principalCentavos: Long, val txnId: Long?)

@Serializable
data class CardPaymentBackup(
    val id: Long, val planId: Long, val n: Long, val dueOn: String, val centavos: Long,
    val interestCentavos: Long, val interestTxnId: Long?, val paidTxnId: Long?,
)

@Serializable
data class InstallmentBackup(
    val id: Long, val iouId: Long, val n: Long, val dueOn: String, val centavos: Long,
    val interestCentavos: Long, val state: String, val paymentId: Long?,
)

@Serializable
data class IouPaymentBackup(
    val id: Long, val iouId: Long, val txnId: Long, val principalCentavos: Long, val interestCentavos: Long,
)

private val BackupJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

fun encodeBackup(file: BackupFile): String = BackupJson.encodeToString(BackupFile.serializer(), file)

const val DamagedFile = "This file is damaged and can't be read. Nothing was changed."
const val NotABackup = "This file isn't a Trackching backup. Nothing was changed."
const val NewerBackup = "This backup is from a newer version of Trackching. Update the app, then try again."

/** What reading a backup file gave us. */
sealed interface Opened {
    data class Ok(val file: BackupFile) : Opened
    data class Refused(val why: String) : Opened
}

/** Reads a backup, or says in plain words why it can't. Never touches the database. */
fun openBackup(text: String): Opened = try {
    val obj = BackupJson.parseToJsonElement(text).jsonObject
    val format = obj["format"]?.jsonPrimitive?.intOrNull
    when {
        obj["app"]?.jsonPrimitive?.contentOrNull != "trackching" -> Opened.Refused(NotABackup)
        format == null -> Opened.Refused(DamagedFile)
        format > BackupFormat -> Opened.Refused(NewerBackup)
        else -> Opened.Ok(BackupJson.decodeFromJsonElement(BackupFile.serializer(), obj))
    }
} catch (e: Exception) {
    Opened.Refused(DamagedFile)
}

fun backupFileName(today: LocalDate): String = "trackching-backup-$today.json"

data class LastBackup(val text: String, val overdue: Boolean)

/** "Never…", or "2026-11-09 · yesterday". After 30 days it's overdue. */
fun lastBackupOf(last: LocalDate?, today: LocalDate): LastBackup {
    if (last == null) return LastBackup("Never. Back up once you've added a few things", false)
    val days = last.daysUntil(today)
    val ago = when (days) {
        0 -> "today"
        1 -> "yesterday"
        else -> "$days days ago"
    }
    val text = "$last · $ago"
    return if (days >= 30) LastBackup("$text. Time for a new one", true) else LastBackup(text, false)
}

/** 123200 -> "1232.00". No ₱ and no commas, so spreadsheets read it as a number. */
fun plainAmount(centavos: Long): String {
    val sign = if (centavos < 0) "-" else ""
    val abs = kotlin.math.abs(centavos)
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

/** One CSV line. A value with a comma, quote or line break gets wrapped in quotes. */
fun csvLine(fields: List<String?>): String = fields.joinToString(",") { value ->
    val v = value ?: ""
    if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
