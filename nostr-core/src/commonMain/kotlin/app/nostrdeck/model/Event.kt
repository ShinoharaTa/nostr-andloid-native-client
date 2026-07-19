package app.nostrdeck.model

/**
 * [#183] Nostr イベントの中核データモデル（UI 非依存・:nostr-core）。
 *
 * NostrEvent には元々 Compose の `@Immutable` が付いていたが、:nostr-core を Compose 非依存に
 * 保つためアノテーションは外し、代わりに composeApp 側の Compose stability 設定
 * （compose_stability.conf）で stable 扱いを維持する（再コンポーズ最適化は不変）。
 */

/** Nostr イベント (kind:1 等)。SQLDelight `events` テーブルと対応。 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val createdAt: Long,
    val content: String,
    val tags: List<List<String>> = emptyList(),
    val sig: String = "",
)

/** 署名前イベント（content/kind/tags を指定し、id・pubkey・sig・created_at は署名時に確定）。 */
data class UnsignedEvent(
    val kind: Int,
    val content: String,
    val tags: List<List<String>> = emptyList(),
    val createdAt: Long = 0,    // 0 のとき署名時に現在時刻を充填
)
