package app.nostrdeck.ui

import androidx.compose.runtime.mutableStateOf
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.model.TimeDisplay
import app.nostrdeck.model.TimeLabels

/**
 * [#930] 時刻の表示の設定。App が設定 Flow から [apply] する snapshot state（Nyan / DeckDensity と同じ作り）。
 * 描画中に [timeLabel] などが読むので、設定を変えると時刻を出している箇所だけが描き直される。
 */
object TimeDisplaySetting {
    private val state = mutableStateOf(TimeDisplay.RELATIVE)
    val mode: TimeDisplay get() = state.value

    /** 値が変わる時のみ代入（DeckWeight.apply と同じ作法）。 */
    fun apply(m: TimeDisplay) {
        if (state.value != m) state.value = m
    }
}

/** 投稿・通知・チャット・記事・ステータスの時刻（設定に従って経過時間か日時）。 */
internal fun timeLabel(createdAt: Long): String =
    TimeLabels.label(createdAt, currentUnixTime(), TimeDisplaySetting.mode) { t, s -> formatAbsoluteTime(t, s) }

/** 投稿の詳細の日時（年から。「日時（秒まで）」なら秒まで）。 */
internal fun detailTimeLabel(createdAt: Long): String =
    formatAbsoluteTime(createdAt, TimeDisplaySetting.mode == TimeDisplay.ABSOLUTE_SECONDS)
