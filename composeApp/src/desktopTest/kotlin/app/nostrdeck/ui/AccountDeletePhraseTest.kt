package app.nostrdeck.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [#953] 削除の確認の語。日本語のキーボードで空白が消えたり全角になったりしても通す。 */
class AccountDeletePhraseTest {
    @Test
    fun accepts_the_phrase_regardless_of_width_case_and_spaces() {
        assertTrue(isAccountDeletePhrase("account delete"))
        assertTrue(isAccountDeletePhrase("  account delete "))
        assertTrue(isAccountDeletePhrase("accountdelete"))
        assertTrue(isAccountDeletePhrase("Account Delete"))
        assertTrue(isAccountDeletePhrase("ａｃｃｏｕｎｔ　ｄｅｌｅｔｅ"))
    }

    @Test
    fun rejects_anything_else() {
        assertFalse(isAccountDeletePhrase(""))
        assertFalse(isAccountDeletePhrase("account"))
        assertFalse(isAccountDeletePhrase("delete account"))
        assertFalse(isAccountDeletePhrase("account deleted"))
    }
}
