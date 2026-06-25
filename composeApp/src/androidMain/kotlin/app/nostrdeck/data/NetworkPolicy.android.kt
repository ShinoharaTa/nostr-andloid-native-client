package app.nostrdeck.data

import app.nostrdeck.model.NetworkTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Android 実装。
 * TODO(whiteboard): ConnectivityManager.registerDefaultNetworkCallback で
 *   NetworkCapabilities を監視し、
 *     - NET_CAPABILITY_NOT_METERED         → UNMETERED
 *     - metered                            → METERED
 *     - RESTRICT_BACKGROUND_STATUS(節約)   → CONSTRAINED
 *     - ネットワーク無し                    → OFFLINE
 *   を callbackFlow で emit する。Context は Application 経由で注入。
 */
actual class NetworkPolicy actual constructor() {
    actual val tier: Flow<NetworkTier> = flowOf(NetworkTier.UNMETERED)
}
