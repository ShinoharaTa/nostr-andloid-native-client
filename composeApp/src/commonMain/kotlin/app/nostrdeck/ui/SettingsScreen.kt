package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.data.SampleData
import app.nostrdeck.signer.SignerMethod
import app.nostrdeck.signer.SignerProvider
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckColors

/**
 * 設定（Android 大画面踏襲）。list-detail 2ペイン：左=メニュー / 右=内容。
 * Expanded では先頭セクションを既定表示。
 */
@Composable
fun SettingsScreen(state: DeckState, isCompact: Boolean) {
    val sections = SampleData.settingsSections
    val selectedId = state.settingsSection ?: if (!isCompact) sections.first().first else null

    TwoPane(
        isCompact = isCompact,
        showDetail = state.settingsSection != null,
        list = { SettingsMenu(selectedId) { state.settingsSection = it } },
        detail = {
            if (selectedId == null) DetailPlaceholder("メニューを選択")
            else SettingsContent(selectedId)
        },
        listWidth = 280,
    )
}

@Composable
private fun SettingsMenu(selectedId: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        Row(Modifier.fillMaxWidth().padding(13.dp, 12.dp)) {
            Text("設定", color = DeckColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(Modifier.fillMaxSize()) {
            items(SampleData.settingsSections, key = { it.first }) { (id, label) ->
                val active = id == selectedId
                Text(
                    label,
                    color = if (active) DeckColors.Accent else DeckColors.Text,
                    fontSize = 13.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (active) DeckColors.AccentWeak else DeckColors.Surface)
                        .clickable { onSelect(id) }.padding(16.dp, 14.dp),
                )
                HorizontalDivider(color = DeckColors.Border)
            }
        }
    }
}

@Composable
private fun SettingsContent(sectionId: String) {
    val title = SampleData.settingsSections.firstOrNull { it.first == sectionId }?.second ?: ""
    Column(Modifier.fillMaxSize().background(DeckColors.Bg).padding(20.dp)) {
        Text(title, color = DeckColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(14.dp))
        when (sectionId) {
            "signer" -> SignerSettings()
            else -> Text("（このセクションは未実装）", color = DeckColors.Text3, fontSize = 13.sp)
        }
    }
}

/** ログイン方法（Signer 抽象の出し分け）。実装済みは LOCAL のみ、他は今後。 */
@Composable
private fun SignerSettings() {
    val current = SignerProvider.current().method
    Text("現在: $current", color = DeckColors.Text2, fontSize = 13.sp)
    Spacer(Modifier.size(12.dp))
    SignerMethod.entries.forEach { m ->
        val done = m == SignerMethod.LOCAL
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(if (m == current) "● " else "○ ", color = DeckColors.Accent, fontSize = 13.sp)
            Text(
                "$m" + if (done) "" else "（未実装）",
                color = if (done) DeckColors.Text else DeckColors.Text3, fontSize = 13.sp,
            )
        }
    }
}
