package com.roombrowser.agent.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.domain.plugins.PluginCard
import com.roombrowser.domain.plugins.PluginCardLink
import com.roombrowser.domain.plugins.PluginCardStyle
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCardShape

// The plugin card's success/warn tints. Material's scheme has an error slot
// and a neutral one but no green or amber, and this app already answered that
// exact question for the composer's include-chips (AgentPanel's IncludeGreen
// pair): material-tuned pairs for the dark-centric palette, picked by theme
// the same way, so a success card reads green and a warning reads amber in
// either theme.
private val PluginSuccessDark = Color(0xFF7DD884)
private val PluginSuccessLight = Color(0xFF1B6E2A)
private val PluginWarnDark = Color(0xFFFFCC80)
private val PluginWarnLight = Color(0xFF8A5A00)

/**
 * One plugin card in the transcript — the plugin's only output that is for
 * the person rather than the model, drawn on the same card surface the chat's
 * own step cards use.
 *
 * Everything on it is already clamped by [PluginCard]'s parse, so this is
 * layout only: a style-tinted icon, an optional bold title, optional body,
 * optional label/value rows, and a link row that opens THROUGH THE APP'S
 * EXTERNAL-URL FLOW on a tap — the same `ACTION_VIEW` the wallet's explorer
 * links use, never an automatic open, because a plugin must not be able to
 * push the user out of the app just by returning a card.
 *
 * [tag] is the row's addressable name (`plugin_card_<sanitized>`, see
 * [pluginCardTag]) — handed in rather than derived here because the tag
 * resolves the plugin's directory name through the INSTALLED list, which only
 * the caller's controller state knows.
 */
@Composable
fun PluginCardView(
    card: PluginCard,
    pluginName: String,
    tag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoomCardShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = tag }
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            // The style is a tint, not a theme swap: one icon in one colour
            // says "this went well / this is a warning" without repainting a
            // card that sits in the same list as the agent's own.
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(
                    cardIcon(card.style),
                    contentDescription = null,
                    tint = cardTint(card.style),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                card.title?.let { title ->
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                }
                card.text?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                }
                card.fields.forEach { field ->
                    Row(
                        Modifier.padding(vertical = 1.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            field.first,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(field.second, style = MaterialTheme.typography.bodySmall)
                    }
                }
                card.link?.let { link ->
                    if (card.title != null || card.text != null || card.fields.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                    }
                    PluginCardLinkRow(link)
                }
                Spacer(Modifier.height(4.dp))
                // Whose card this is — the one line that keeps a plugin's
                // voice distinguishable from the agent's own answers.
                Text(
                    "via $pluginName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The card's link row: the label (or the bare URL when the plugin gave none),
 * tappable, opening outside the app exactly the way the wallet's explorer
 * links do. The URL is already validated http(s) by the domain parse — an
 * `ACTION_VIEW` that fails finds no handler and is swallowed, and nothing
 * ever opens without the tap.
 */
@Composable
private fun PluginCardLinkRow(link: PluginCardLink) {
    val context = LocalContext.current
    Row(
        Modifier
            .clip(RoomCardShape)
            .clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri())) }
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            link.label.ifBlank { link.url },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The icon each card style reads as, in the same slot the tool cards use. */
private fun cardIcon(style: PluginCardStyle): ImageVector = when (style) {
    PluginCardStyle.INFO -> Icons.Filled.Info
    PluginCardStyle.SUCCESS -> Icons.Filled.CheckCircle
    PluginCardStyle.WARN -> Icons.Filled.Warning
    PluginCardStyle.ERROR -> Icons.Filled.ErrorOutline
}

/** The tint each card style carries: neutral, green, amber, error. */
@Composable
private fun cardTint(style: PluginCardStyle): Color {
    val dark = LocalRoomExtras.current.dark
    return when (style) {
        PluginCardStyle.INFO -> MaterialTheme.colorScheme.primary
        PluginCardStyle.SUCCESS -> if (dark) PluginSuccessDark else PluginSuccessLight
        PluginCardStyle.WARN -> if (dark) PluginWarnDark else PluginWarnLight
        PluginCardStyle.ERROR -> MaterialTheme.colorScheme.error
    }
}

/**
 * The addressable tag of one card row: `plugin_card_<dirName>`.
 *
 * The transcript persists the plugin's DISPLAY name (AgentEntry.Plugin was
 * built from it), while the e2e/a11y contract wants the plugin's identity —
 * so the tag resolves the name through the installed list, whose rows carry
 * the directory name the whole plugin system addresses things by. A card
 * whose plugin is no longer installed falls back to a sanitized name: the
 * row keeps a stable, readable tag instead of losing the addressable name
 * for being historical.
 */
internal fun pluginCardTag(installed: List<PluginEntity>, pluginName: String): String {
    val dirName = installed.firstOrNull { it.name == pluginName }?.dirName
    val sanitized = dirName ?: pluginName.lowercase()
        .map { if (it.isLetterOrDigit()) it else '_' }
        .joinToString("")
    return "plugin_card_$sanitized"
}
