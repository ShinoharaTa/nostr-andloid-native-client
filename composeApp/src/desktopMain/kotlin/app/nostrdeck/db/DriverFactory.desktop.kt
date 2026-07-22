package app.nostrdeck.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

/**
 * [#218] Desktop(JVM) 実装。JDBC SQLite ドライバでファイル DB を開く。
 * 新規ファイル時のみ [NostrDb.Schema] を作成する（spike: マイグレーションは Phase2）。
 */
actual class DriverFactory(private val dbFile: File) {
    actual fun createDriver(): SqlDriver {
        val fresh = !dbFile.exists()
        dbFile.parentFile?.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        if (fresh) NostrDb.Schema.create(driver)
        return driver
    }
}
