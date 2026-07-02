package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/*
 * Deck 共通コントロール。M3 標準（Button/OutlinedTextField/TextButton/AlertDialog 既定色）は
 * 使わず、モノクロ・フラットな Deck の見た目に統一する（原則: 面と余白、線と影は最小限）。
 */

/** 主ボタン（塗り: 白地+暗文字）。送信/追加/保存などの主アクション用。 */
@Composable
fun DeckButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .heightIn(min = DeckDimens.TouchTargetSm)
            .clip(RoundedCornerShape(DeckRadius.Full))
            .background(if (enabled) DeckColors.Text else DeckColors.Surface3)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = DeckSpace.Lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) DeckColors.Bg else DeckColors.Text3,
            fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong)
    }
}

/** 副ボタン（Surface2 の面）。戻る/キャンセル/フォロー中などの控えめアクション用。 */
@Composable
fun DeckGhostButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .heightIn(min = DeckDimens.TouchTargetSm)
            .clip(RoundedCornerShape(DeckRadius.Full))
            .background(DeckColors.Surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = DeckSpace.Lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) DeckColors.Text else DeckColors.Text3,
            fontSize = DeckType.Sub, fontWeight = DeckWeight.Link)
    }
}

/** 文字だけのボタン（ダイアログのアクションや行内リンク）。 */
@Composable
fun DeckTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = DeckColors.Text,
) {
    Box(
        modifier
            .heightIn(min = DeckDimens.TouchTargetXs)
            .clip(RoundedCornerShape(DeckRadius.Sm))
            .clickable(onClick = onClick)
            .padding(horizontal = DeckSpace.Sm),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = color, fontSize = DeckType.Sub, fontWeight = DeckWeight.Link) }
}

/**
 * 1行入力（Surface2 のピル面・チャット入力欄と同スタイル）。
 * [inputModifier] は内側の BasicTextField に渡す（secretAutofill 等のセマンティクス用）。
 */
@Composable
fun DeckTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    inputModifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .heightIn(min = DeckDimens.TouchTargetSm)
            .clip(RoundedCornerShape(DeckRadius.Full))
            .background(DeckColors.Surface2)
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = TextStyle(color = DeckColors.Text, fontSize = DeckType.Caption),
                cursorBrush = SolidColor(DeckColors.Accent),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                modifier = inputModifier.fillMaxWidth(),
            )
        }
        trailing?.let {
            Spacer(Modifier.width(DeckSpace.Sm))
            it()
        }
    }
}

/**
 * 確認/情報ダイアログ（Deck スタイル）。破壊的操作は [destructive]=true で
 * 実行ボタンを Warn 色に。[onDismiss]=キャンセル。[dismissLabel]=null で情報ダイアログ（確認のみ）。
 */
@Composable
fun DeckConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String? = "キャンセル",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeckColors.Surface,
        shape = RoundedCornerShape(DeckRadius.Lg),
        title = { Text(title, color = DeckColors.Text, fontSize = DeckType.Title, fontWeight = DeckWeight.Strong) },
        text = { Text(text, color = DeckColors.Text2, fontSize = DeckType.Sub, lineHeight = DeckType.LineTitle) },
        confirmButton = {
            DeckTextButton(confirmLabel, onClick = onConfirm,
                color = if (destructive) DeckColors.Warn else DeckColors.Text)
        },
        dismissButton = dismissLabel?.let {
            { DeckTextButton(it, onClick = onDismiss, color = DeckColors.Text3) }
        },
    )
}

/** 1行入力のダイアログ（新規 DM の宛先入力など）。 */
@Composable
fun DeckInputDialog(
    title: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeckColors.Surface,
        shape = RoundedCornerShape(DeckRadius.Lg),
        title = { Text(title, color = DeckColors.Text, fontSize = DeckType.Title, fontWeight = DeckWeight.Strong) },
        text = {
            DeckTextField(value = value, onValueChange = onValueChange, placeholder = placeholder,
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            DeckTextButton(confirmLabel, onClick = { if (confirmEnabled) onConfirm() },
                color = if (confirmEnabled) DeckColors.Text else DeckColors.Text3)
        },
        dismissButton = { DeckTextButton("キャンセル", onClick = onDismiss, color = DeckColors.Text3) },
    )
}
