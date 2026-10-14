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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.node.NodeRuntime
import com.roombrowser.node.packages.HttpRegistry
import com.roombrowser.node.packages.InstalledPackage
import com.roombrowser.node.packages.NodePackageStore
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.RoomBrowserTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The npm packages installed beside the embedded Node runtime — its OWN
 * activity, so the list is a place the user can read and prune rather than a
 * directory they cannot see. Every delete here is immediate; a package is
 * recoverable by installing it again.
 *
 * Runs in the DEFAULT process like the other agent settings screens. The
 * packages are plain files under this app's data directory, which every process
 * of this app shares, so the list reads the same tree `node_run` requires from —
 * and it never starts the runtime to do it.
 */
class AgentPackagesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoomBrowserTheme {
                AgentPackagesRoot(onClose = { finish() })
            }
        }
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, AgentPackagesActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}

@Composable
private fun AgentPackagesRoot(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { NodePackageStore(NodeRuntime.directory(context), HttpRegistry()) }
    val runtimeAvailable = remember { NodeRuntime.available }
    var packages by remember { mutableStateOf<List<InstalledPackage>?>(null) }

    suspend fun load() {
        packages = withContext(Dispatchers.IO) { store.installed() }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Node packages") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
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
            if (!runtimeAvailable) {
                Text(
                    "This build carries no Node runtime for this device's CPU, so none of these " +
                        "packages can be used here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            val list = packages
            if (list == null) return@Column
            if (list.isEmpty()) {
                EmptyState(
                    title = "No packages installed",
                    subtitle = "Packages the agent installs with node_packages appear here, so you " +
                        "can see and delete them. Nothing is installed until the agent asks."
                )
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        "Shared by every profile and stored in this app's own data directory. " +
                            "Plain JavaScript only: a package with native code installs but fails " +
                            "to load, and a package's own install script never runs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                items(list, key = { it.name }) { pkg ->
                    PackageRow(
                        pkg = pkg,
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.remove(pkg.name) }
                                load()
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun PackageRow(pkg: InstalledPackage, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                pkg.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                pkg.version ?: "version unreadable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete ${pkg.name}")
        }
    }
}
