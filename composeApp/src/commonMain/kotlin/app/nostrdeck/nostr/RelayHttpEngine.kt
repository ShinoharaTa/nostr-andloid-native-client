package app.nostrdeck.nostr

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * [#849] リレー接続用の HttpClient を WebSocket の ping 付きで作る。共通の設定は [block] で足す。
 * ping は、無通信の接続をリレーやプロキシ・NAT に切られないために送る。効かせ方はエンジンごとに違う。
 *  - Android(OkHttp): WebSockets プラグインの pingIntervalMillis は効かない。OkHttp のセッションは最初から
 *    DefaultWebSocketSession なので、プラグインは包み直さない（プラグインの pinger が動かない）。ping 間隔は
 *    OkHttpClient の pingInterval（既定 0 = 無効）を読むだけなので、エンジン設定の pingInterval で送る。
 *  - iOS(Darwin) / Desktop(CIO): エンジンのセッションをプラグインが DefaultWebSocketSession で包み、
 *    pingIntervalMillis ごとに ping を送る（Darwin は sendPingWithPongReceiveHandler、CIO は Ping フレーム）。
 * どのエンジンでも、pong が返らなければ接続を失敗させる（接続モニタには READ_ERROR として出る）。
 */
internal expect fun relayHttpClient(pingIntervalMillis: Long, block: HttpClientConfig<*>.() -> Unit): HttpClient
