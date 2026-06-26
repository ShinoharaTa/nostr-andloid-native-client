package app.nostrdeck.db

import app.cash.sqldelight.db.SqlDriver

/**
 * SQLDelight ドライバ生成（Android=AndroidSqliteDriver / iOS=NativeSqliteDriver）。
 *
 * マイグレーション運用: スキーマを変更したら version を上げ、<prev>.sqm を追加する
 * （例: v2→v3 なら 2.sqm）。ドライバには常に NostrDb.Schema を渡しているため、
 * 端末上の DB の version がスキーマ version より低いと、該当する .sqm が順に適用され、
 * アンインストール無しで既存 DB が更新される。
 */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}

/** ドライバから DB を生成。 */
fun createDatabase(factory: DriverFactory): NostrDb = NostrDb(factory.createDriver())
