package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.terms_agree
import nostr_deck_client.composeapp.generated.resources.terms_lead
import nostr_deck_client.composeapp.generated.resources.terms_point_conduct
import nostr_deck_client.composeapp.generated.resources.terms_point_nostr
import nostr_deck_client.composeapp.generated.resources.terms_point_response
import nostr_deck_client.composeapp.generated.resources.terms_point_tools
import nostr_deck_client.composeapp.generated.resources.terms_read_full
import nostr_deck_client.composeapp.generated.resources.terms_title

/** [#951] 利用規約の全文（GitHub Pages の docs/terms.html）。 */
const val TERMS_URL = "https://nostrism.shino3.net/terms.html"
const val PRIVACY_POLICY_URL = "https://nostrism.shino3.net/privacy-policy.html"

/**
 * [#951] 利用規約の同意の画面。App Store ガイドライン 1.2 のとおり、ログインの方法を選ぶ前（[LoginGate] の前）に出す。
 * すでにログインしている利用者にも、同意していない版なら一度出す（同意した版は [app.nostrdeck.data.EventRepository.acceptTerms] が覚える）。
 */
@Composable
fun TermsGate(onAgree: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxSize().background(DeckColors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(DeckSpace.Lg),
    ) {
        Spacer(Modifier.size(DeckSpace.Xl))
        AppMark(Modifier.size(56.dp))
        Spacer(Modifier.size(DeckSpace.Md))
        Text(stringResource(Res.string.terms_title), color = DeckColors.Text, fontSize = DeckType.Emoji, fontWeight = DeckWeight.Strong)
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(stringResource(Res.string.terms_lead), color = DeckColors.Text3, fontSize = DeckType.Sub)
        Spacer(Modifier.size(DeckSpace.Lg))
        HorizontalDivider(color = DeckColors.Border)
        Spacer(Modifier.size(DeckSpace.Lg))
        listOf(
            Res.string.terms_point_conduct,
            Res.string.terms_point_tools,
            Res.string.terms_point_response,
            Res.string.terms_point_nostr,
        ).forEach { point ->
            Row(Modifier.fillMaxWidth().padding(bottom = DeckSpace.Md)) {
                Text("・", color = DeckColors.Text2, fontSize = DeckType.Body)
                Spacer(Modifier.width(DeckSpace.Xs))
                Text(stringResource(point), color = DeckColors.Text, fontSize = DeckType.Body, modifier = Modifier.weight(1f))
            }
        }
        DeckTextButton(stringResource(Res.string.terms_read_full), onClick = { uri.openUri(TERMS_URL) }, color = DeckColors.Accent)
        Spacer(Modifier.size(DeckSpace.Lg))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            DeckButton(stringResource(Res.string.terms_agree), onClick = onAgree, modifier = Modifier.fillMaxWidth())
        }
    }
}
