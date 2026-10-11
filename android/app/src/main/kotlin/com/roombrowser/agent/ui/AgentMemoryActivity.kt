@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.AgentSettingsController
import com.roombrowser.data.db.AgentSiteMemoryEntity
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.RoomBrowserTheme

/**
 * What the agent has remembered, grouped by site — its OWN activity, so the
 * list is a place the user can actually read and prune rather than a hidden
 * table. Every delete here is immediate and irreversible; only "Forget
 * everything" asks first, because it is the one that clears every site at once.
 *
 * Runs in the DEFAULT process like the other agent settings activities: the
 * writes land in the shared Room database and the browser process sees them
 * through multi-instance invalidation.
 */
class AgentMemoryActivity : ComponentActivity() {

    private lateinit var controller: AgentSettingsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = AgentSettingsController(application, intent.getStringExtra(EXTRA_PROFILE_ID))
        controller.start()
        setContent {
            RoomBrowserTheme {
                AgentMemoryRoot(controller = controller, onClose = { finish() })
            }
        }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        fun launch(context: Context, profileId: String?) {
            val intent = Intent(context, AgentMemoryActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}

@Composable
private fun AgentMemoryRoot(controller: AgentSettingsController, onClose: () -> Unit) {
    var confirmForgetAll by remember { mutableStateOf(false) }
    val memories = controller.siteMemory

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Agent memory") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
                actions = {
                    if (memories.isNotEmpty()) {
                        IconButton(onClick = { confirmForgetAll = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Forget everything")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
        ) {
            if (memories.isEmpty()) {
                EmptyState(
                    title = "Nothing remembered yet",
                    subtitle = "When the agent keeps a note about a site — and only then if you " +
                        "turn on \"Let the agent remember this site\" — it appears here, grouped " +
                        "by site. This profile is the only one that can read it."
                )
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                memories.groupBy { it.origin }.forEach { (origin, entries) ->
                    item(key = "origin:$origin") {
                        SiteHeader(origin = origin, onForgetSite = { controller.forgetSite(origin) })
                    }
                    items(entries, key = { "entry:${it.origin}:${it.key}" }) { entry ->
                        MemoryRow(
                            entry = entry,
                            onForget = { controller.forgetMemory(entry.origin, entry.key) }
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (confirmForgetAll) {
        AlertDialog(
            onDismissRequest = { confirmForgetAll = false },
            title = { Text("Forget everything?") },
            text = {
                Text(
                    "This permanently deletes every note the agent has kept about every site, for " +
                        "this profile. It cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.forgetAllMemory()
                    confirmForgetAll = false
                }) { Text("Forget everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForgetAll = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SiteHeader(origin: String, onForgetSite: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Memory,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            origin,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onForgetSite) {
            Icon(Icons.Filled.Delete, contentDescription = "Forget this site")
        }
    }
}

@Composable
private fun MemoryRow(entry: AgentSiteMemoryEntity, onForget: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.key,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onForget) {
            Icon(Icons.Filled.Delete, contentDescription = "Forget \"${entry.key}\"")
        }
    }
}
