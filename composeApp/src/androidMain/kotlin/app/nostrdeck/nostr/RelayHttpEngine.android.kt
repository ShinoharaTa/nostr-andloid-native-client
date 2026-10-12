package app.nostrdeck.nostr

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit

/**
 * [#849] Android のエンジンは OkHttp。OkHttp はプラグインの pingIntervalMillis を使わないので、
 * OkHttpClient の pingInterval で ping を送る。次の ping までに pong が返らなければ、OkHttp が接続を失敗させる。
 */
internal actual fun relayHttpClient(pingIntervalMillis: Long, block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) {
        engine { config { pingInterval(pingIntervalMillis, TimeUnit.MILLISECONDS) } }
        install(WebSockets)
        block()
    }
