package app.nostrdeck.ui

/**
 * iOS のエッジスワイプ戻るを共通の戻る処理へ橋渡しするホルダー。
 * [PlatformBackHandler] の iOS 実装が composition 中に現在の [onBack]/[enabled] を登録し、
 * MainViewController に付けた UIScreenEdgePanGestureRecognizer が [dispatch] を呼ぶ。
 *
 * 登録元は AppScaffold の1箇所だけなので単一ホルダーで足りる。
 */
object IosBackDispatcher {
    var enabled: Boolean = false
    var onBack: (() -> Unit)? = null

    /** 左端スワイプ完了時に呼ばれる。有効なときだけ戻る。 */
    fun dispatch() {
        if (enabled) onBack?.invoke()
    }
}
