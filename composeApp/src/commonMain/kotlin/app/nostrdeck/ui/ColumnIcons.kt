package app.nostrdeck.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.ui.graphics.vector.ImageVector
import app.nostrdeck.model.ColumnKind

/** カラム種別 → ヘッダ/レールのアイコン。 */
fun columnIcon(kind: ColumnKind): ImageVector = when (kind) {
    ColumnKind.FOLLOWING -> Icons.Outlined.Home
    ColumnKind.HASHTAG -> Icons.Outlined.Tag
    ColumnKind.NOTIFICATIONS -> Icons.Outlined.Notifications
    ColumnKind.DM -> Icons.Outlined.MailOutline
    ColumnKind.GLOBAL -> Icons.Outlined.Public
    ColumnKind.PROFILE -> Icons.Outlined.Person
    ColumnKind.FAVS -> Icons.Outlined.StarBorder
    ColumnKind.THREAD -> Icons.AutoMirrored.Outlined.Reply
    ColumnKind.CHANNEL_LIST -> Icons.Outlined.Tag
    ColumnKind.CHANNEL_ROOM -> Icons.AutoMirrored.Outlined.Chat
}
