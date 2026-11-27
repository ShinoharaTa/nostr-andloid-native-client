package app.nostrdeck.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [#961] 開発者への知らせの文面。Web の developerNotice と同じ文字列になること（npub / note1 は nostr-tools で出した値）。
 */
class DeveloperNoticeTest {

    private val user = "42a7b8818aa6eae298a9712d887821933b2a68b1987bb58e694ee8aae5b923a2"
    private val post = "306fce48494ec3ef8690064ee7b3e89b92bdc0cc5fb5f60a56b28a6a49343ba4"
    private val npub = "npub1g2nm3qv25m4w9x9fwykcs7ppjvaj5693npamtrnffm524edeyw3q4plfu2"
    private val note = "note1xphuujzffmp7lp5sqe8w0vlgnwftmsxvt76lvzjkk29x5jf58wjqve756m"

    @Test
    fun block_from_a_post_and_from_a_profile() {
        assertEquals("[Nostrism] block\nuser: nostr:$npub\npost: nostr:$note", DeveloperNotice.block(user, post))
        assertEquals("[Nostrism] block\nuser: nostr:$npub", DeveloperNotice.block(user, null))
    }

    @Test
    fun report_of_a_post_and_of_a_user() {
        assertEquals("[Nostrism] report: spam\nuser: nostr:$npub\npost: nostr:$note", DeveloperNotice.report(user, post, "spam"))
        assertEquals("[Nostrism] report: illegal\nuser: nostr:$npub", DeveloperNotice.report(user, null, "illegal"))
        assertEquals("[Nostrism] report: other\nuser: nostr:$npub\nreason: 詐欺の誘導", DeveloperNotice.report(user, null, "other", "詐欺の誘導"))
    }
}
