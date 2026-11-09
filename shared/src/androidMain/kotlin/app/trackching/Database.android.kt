package app.trackching

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.trackching.db.TrackchingDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Opens (or creates) trackching.db in the app's private storage. */
fun createDatabase(context: Context): TrackchingDatabase {
    val driver = AndroidSqliteDriver(
        schema = TrackchingDatabase.Schema,
        context = context,
        name = "trackching.db",
        callback = object : AndroidSqliteDriver.Callback(TrackchingDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                db.setForeignKeyConstraintsEnabled(true)
            }
        },
    )
    return TrackchingDatabase(driver)
}