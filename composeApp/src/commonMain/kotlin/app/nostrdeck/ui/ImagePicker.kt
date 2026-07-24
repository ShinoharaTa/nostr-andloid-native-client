package app.nostrdeck.ui

import androidx.compose.runtime.Composable

/**
 * [M11] 端末のメディアピッカーから画像を選ぶ抽象（複数選択対応）。
 * Compose から [rememberImagePicker] で取得し、[ImagePicker.launch] でシステムの選択画面を開く。
 * 選択されると [PickedImage]（生バイト + MIME + ファイル名）の一覧が onPicked へ渡る。
 *
 * Android は PickMultipleVisualMedia（フォトピッカー・複数選択）。iOS は当面 no-op スタブ。
 */
data class PickedImage(val bytes: ByteArray, val mime: String, val name: String)

expect class ImagePicker {
    fun launch()
}

@Composable
expect fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): ImagePicker

/**
 * [#202] 端末のメディアピッカーから動画を1本選ぶ抽象。
 * 受け渡しは画像と同じ [PickedImage]（生バイト + MIME + ファイル名）を流用し、
 * アップロード経路(NIP-96)を共用する。動画は圧縮しない（原バイトのまま送る）。
 *
 * Android は PickVisualMedia(VideoOnly・単一選択)。
 * [#224] キャンセル（未選択で閉じた）時は null で呼ぶ。呼び出し側はキーボード復帰などの
 * 「ピッカーから戻った」後処理をキャンセル時にも実行できる。
 */
@Composable
expect fun rememberVideoPicker(onPicked: (PickedImage?) -> Unit): ImagePicker
