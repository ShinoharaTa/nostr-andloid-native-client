package app.nostrdeck.signer

import android.content.Context

/** [#41] NIP-46 セッションを SharedPreferences に保存する Android 実装。 */
class SharedPrefsNip46Store(private val appContext: Context) : Nip46Store {
    private fun prefs() = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    override fun save(json: String) = prefs().edit().putString(KEY, json).apply()
    override fun load(): String? = prefs().getString(KEY, null)
    override fun clear() = prefs().edit().remove(KEY).apply()

    private companion object {
        const val PREF = "nip46_session"
        const val KEY = "session"
    }
}
