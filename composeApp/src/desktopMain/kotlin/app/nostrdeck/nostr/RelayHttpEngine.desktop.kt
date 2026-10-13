package app.nostrdeck.nostr

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

/** [#849] Desktop のエンジンは CIO。プラグインが DefaultWebSocketSession で包んで ping を送る。 */
internal actual fun relayHttpClient(pingIntervalMillis: Long, block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(CIO) {
        install(WebSockets) { this.pingIntervalMillis = pingIntervalMillis }
        block()
    }
