package app.nostrdeck.crypto

/** プラットフォーム依存の暗号プリミティブ。 */

/** 暗号学的に安全な乱数 [size] バイト。鍵生成・Schnorr の aux rand に使う。 */
expect fun secureRandomBytes(size: Int): ByteArray

/** 現在の Unix 時刻（秒）。イベントの created_at に使う。 */
expect fun currentUnixTime(): Long
