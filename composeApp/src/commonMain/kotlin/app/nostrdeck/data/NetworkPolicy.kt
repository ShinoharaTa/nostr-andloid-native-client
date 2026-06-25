package app.nostrdeck.data

import app.nostrdeck.model.NetworkTier
import kotlinx.coroutines.flow.Flow

/**
 * 回線種別の判定（whiteboard.md「回線種別に応じた制御」）。
 * - Android: ConnectivityManager / NetworkCapabilities
 * - iOS:     NWPathMonitor
 * UI/購読ロジックは参照せず、Repository 層だけがこれを見て更新方針を変える。
 */
expect class NetworkPolicy() {
    val tier: Flow<NetworkTier>
}

/**
 * ティアごとの更新方針。kind:0 の TTL や画像画質はここから引く。
 * tokens 同様、判断を1箇所に集約してテスト可能にするのが狙い。
 */
data class RefreshPolicy(
    val profileTtlMillis: Long,
    val prefetchAvatars: Boolean,
    val fullResImages: Boolean,
    val negentropyFullSync: Boolean,
    val backgroundSync: Boolean,
) {
    companion object {
        fun forTier(tier: NetworkTier): RefreshPolicy = when (tier) {
            NetworkTier.UNMETERED -> RefreshPolicy(
                profileTtlMillis = 60 * 60 * 1000,        // 1h: 積極リフレッシュ
                prefetchAvatars = true, fullResImages = true,
                negentropyFullSync = true, backgroundSync = true,
            )
            NetworkTier.METERED -> RefreshPolicy(
                profileTtlMillis = 24 * 60 * 60 * 1000,   // 24h: 節約
                prefetchAvatars = false, fullResImages = false,
                negentropyFullSync = false, backgroundSync = false,
            )
            NetworkTier.CONSTRAINED -> RefreshPolicy(
                profileTtlMillis = 7 * 24 * 60 * 60 * 1000L, // 1w: キャッシュのみ寄り
                prefetchAvatars = false, fullResImages = false,
                negentropyFullSync = false, backgroundSync = false,
            )
            NetworkTier.OFFLINE -> RefreshPolicy(
                profileTtlMillis = Long.MAX_VALUE,        // 取りに行かない
                prefetchAvatars = false, fullResImages = false,
                negentropyFullSync = false, backgroundSync = false,
            )
        }
    }
}
