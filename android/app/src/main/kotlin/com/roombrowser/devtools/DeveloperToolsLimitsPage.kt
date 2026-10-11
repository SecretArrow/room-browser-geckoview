package com.roombrowser.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCopyButton

/**
 * "What this cannot do, and why" — the whole screen.
 *
 * Every per-edition fact is read off [DeveloperToolsCapabilities] at render
 * time (see [devToolsLimits]), so this screen explains each absence with the
 * same reason the capability layer reports and cannot drift from it. The
 * edition-independent statements are the only fixed prose, and each is a
 * statement of absence rather than a claim about a feature.
 *
 * It is rendered inside the panel's existing scrolling body, so it adds no
 * scroll container of its own.
 */
@Composable
internal fun DeveloperToolsLimitsPage(
    engineName: String,
    capabilities: DeveloperToolsCapabilities,
    onBack: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val sections = remember(engineName, capabilities) { devToolsLimits(capabilities) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = extras.icon)
            }
            Text(
                "What this cannot do, and why",
                style = MaterialTheme.typography.titleSmall,
                color = extras.textPrimary,
                modifier = Modifier.weight(1f)
            )
            RoomCopyButton(
                devToolsLimitsText(engineName, capabilities),
                "Limits",
                "Copy what these tools cannot do and why"
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "This is the exact set of gaps this edition has, and the reason for each. A " +
                    "capability that is here is one this build can actually serve; one that is " +
                    "described below is one it cannot — not one that is merely empty.",
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            sections.forEach { section ->
                Text(
                    section.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = extras.primary,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                )
                section.rows.forEach { row -> LimitsCard(row) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LimitsCard(row: LimitsRow) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
            .background(extras.surfaceAlt.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            row.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            row.detail,
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary
        )
    }
}
