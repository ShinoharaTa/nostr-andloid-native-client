package app.nostrdeck.data

import app.nostrdeck.model.NetworkTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * iOS 実装。
 * TODO(whiteboard): NWPathMonitor を生成し pathUpdateHandler を callbackFlow 化。
 *     - path.status != satisfied  → OFFLINE
 *     - path.isConstrained (Low Data Mode) → CONSTRAINED
 *     - path.isExpensive (セルラー/テザリング) → METERED
 *     - それ以外                  → UNMETERED
 */
actual class NetworkPolicy actual constructor() {
    actual val tier: Flow<NetworkTier> = flowOf(NetworkTier.UNMETERED)
}
