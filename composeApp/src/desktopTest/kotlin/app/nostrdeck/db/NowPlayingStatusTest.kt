package app.nostrdeck.db

import app.nostrdeck.data.EventRepository
import app.nostrdeck.model.NowPlaying
import app.nostrdeck.model.NowPlayingTrack
import app.nostrdeck.model.UserStatuses
import app.nostrdeck.signer.InMemoryKeyVault
import app.nostrdeck.signer.LocalSigner
import app.nostrdeck.signer.SignerProvider
import app.nostrdeck.wallet.LocalRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kotlincrypto.hash.sha2.SHA256
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [#1021] Now Playing: 聴いている曲のステータス（`d=music`）と、手で打つ 1 行のステータス（`d=general`）は別のもの。
 * 片方を出しても・消しても、もう片方は残る。リレー（テスト内のもの）が受理したことを確かめてから手元に反映する。
 */
class NowPlayingStatusTest {
    private val secret = SHA256().digest("now-playing-status-me".encodeToByteArray())

    @Test
    fun the_music_status_and_the_general_status_do_not_overwrite_each_other(): Unit = runBlocking {
        val relay = LocalRelay().also { it.start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            SignerProvider.use(LocalSigner(InMemoryKeyVault().apply { importPrivateKey(secret) }))
            val db = createDatabase(DriverFactory(File.createTempFile("now-playing-status", ".db").apply { deleteOnExit() }))
            val repo = EventRepository(db, scope, listOf(relay.url))
            repo.start()
            val me = withTimeout(5_000) { repo.loggedInPubkey().first { it != null } }!!
            suspend fun status(type: UserStatuses.Type) =
                repo.statusesOfFlow(me).first().firstOrNull { UserStatuses.typeOf(it) == type }

            assertTrue(repo.publishUserStatus("作業中", null), "general を出す")
            val track = NowPlayingTrack("Intergalactic", "Beastie Boys", durationSec = 231, positionSec = 31, app = NowPlaying.App.SPOTIFY)
            assertTrue(repo.publishMusicStatus(track), "music を出す")

            val music = assertNotNull(status(UserStatuses.Type.MUSIC))
            assertEquals("Intergalactic - Beastie Boys", music.content)
            assertNotNull(UserStatuses.expirationOf(music), "曲が終わる時刻の期限が付く")
            assertEquals(
                UserStatuses.Pointer.Url("https://open.spotify.com/search/Intergalactic%20-%20Beastie%20Boys"),
                UserStatuses.pointerOf(music),
            )
            assertEquals("作業中", status(UserStatuses.Type.GENERAL)?.content, "music を出しても general は残る")

            // 同じ曲をもう一度出す（同じ秒でも、前の版より後の時刻になって置き換わる）
            assertTrue(repo.publishMusicStatus(track))
            assertTrue(assertNotNull(status(UserStatuses.Type.MUSIC)).createdAt > music.createdAt)

            assertTrue(repo.clearMusicStatus(), "music を消す")
            assertEquals("", status(UserStatuses.Type.MUSIC)?.content, "空の本文で消える")
            assertEquals("作業中", status(UserStatuses.Type.GENERAL)?.content, "music を消しても general は残る")

            assertTrue(repo.publishUserStatus("", null), "general を消す")
            assertEquals("", status(UserStatuses.Type.GENERAL)?.content)
        } finally {
            scope.cancel()
            relay.stop()
        }
    }
}
