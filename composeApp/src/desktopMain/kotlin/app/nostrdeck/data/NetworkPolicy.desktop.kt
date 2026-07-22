package app.nostrdeck.data

import app.nostrdeck.model.NetworkTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// [#218] Desktop 実装。有線/Wi-Fi 前提で常に UNMETERED（回線種別の細分は Phase2）。
actual class NetworkPolicy actual constructor() {
    actual val tier: Flow<NetworkTier> = flowOf(NetworkTier.UNMETERED)
}
