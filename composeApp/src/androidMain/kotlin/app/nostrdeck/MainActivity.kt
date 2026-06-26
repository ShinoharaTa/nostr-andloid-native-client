package app.nostrdeck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.nostrdeck.data.EventRepository
import app.nostrdeck.db.DriverFactory
import app.nostrdeck.db.createDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** M1: 既定リレー（公開リレー）。設定で変更可能にするのは M5/M7。 */
private val DEFAULT_RELAYS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
)

class MainActivity : ComponentActivity() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: EventRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val db = createDatabase(DriverFactory(applicationContext))
        repository = EventRepository(db, appScope, DEFAULT_RELAYS).apply {
            start()
            subscribeHomeFeed()
        }

        setContent { App(repository) }
    }

    override fun onDestroy() {
        appScope.cancel()
        super.onDestroy()
    }
}
