package app.nostrdeck.db

import app.cash.sqldelight.db.SqlDriver

/** SQLDelight ドライバ生成（Android=AndroidSqliteDriver / iOS=NativeSqliteDriver）。 */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}

/** ドライバから DB を生成。 */
fun createDatabase(factory: DriverFactory): NostrDb = NostrDb(factory.createDriver())
