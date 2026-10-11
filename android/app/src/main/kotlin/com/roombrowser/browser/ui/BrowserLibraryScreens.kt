package com.roombrowser.browser.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.StatCategories
import com.roombrowser.data.db.DownloadEntity
import com.roombrowser.data.repo.DownloadStatus
import com.roombrowser.domain.download.DownloadFormat
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomCard
import com.roombrowser.ui.common.RoomCardShape
import com.roombrowser.ui.common.RoomSheetHeader
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.StatTile
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems

/**
 * Library screens (Tabs / Bookmarks / History / Downloads / Privacy
 * Dashboard) — quiet rounded card lists on the themed background.
 *
 * Insets are already applied by the host BrowserScreen (status bar top +
 * horizontal + bottom bar); the top bars below set explicit zero window
 * insets so nothing is double-applied.
 */

/** Shared top bar: themed container, back affordance, zero extra insets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    title: String,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val extras = LocalRoomExtras.current
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
            }
        },
        actions = actions,
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = extras.background,
            scrolledContainerColor = extras.background,
            navigationIconContentColor = extras.textPrimary,
            titleContentColor = extras.textPrimary,
            actionIconContentColor = extras.icon
        )
    )
}

/** Centered empty state filling the remaining space below the top bar. */
@Composable
private fun CenteredEmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        EmptyState(title, subtitle)
    }
}

/**
 * Small glyph with a full-size touch target — no visual bulk.
 *
 * The box used to be exactly 40dp, and because the `clickable` sits inside
 * it that 40dp WAS the hit rect: every Close / Delete affordance in this
 * file — the only way to close a tab from the list, the only way to remove
 * a bookmark or a history row — sat 8dp under the 48dp accessibility
 * minimum. Only the hit rect grows here; the glyph is still 17dp, so
 * nothing on screen gets heavier.
 */
@Composable
private fun QuietIconButton(
    contentDescription: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = LocalRoomExtras.current.icon, modifier = Modifier.size(17.dp))
    }
}

/**
 * The two bulk closes behind a tab card's menu, with the copy for the
 * confirmation each one has to pass first.
 *
 * [onlyLeft] is the argument `BrowserViewModel.closeOtherTabs` takes: null
 * sweeps every other tab, true only the ones positioned before this card.
 * Both are destructive at a scale the per-card Close button is not —
 * "Reopen closed tab" restores exactly one tab, so a sweep of a dozen is
 * effectively irreversible.
 */
private enum class BulkTabClose(
    val onlyLeft: Boolean?,
    val menuLabel: String,
    val title: String,
    val prompt: String,
    val confirmLabel: String
) {
    OTHERS(
        onlyLeft = null,
        menuLabel = "Close other tabs",
        title = "Close every other tab?",
        prompt = "Every tab in this profile except this one is closed. Reopen closed tab brings back only the last one.",
        confirmLabel = "Close others"
    ),
    LEFT(
        onlyLeft = true,
        menuLabel = "Close tabs to the left",
        title = "Close the tabs before this one?",
        prompt = "Every tab positioned before this one is closed. Reopen closed tab brings back only the last one.",
        confirmLabel = "Close left"
    )
}

/** Tab grid / list (spec section 12) — per-profile tab collection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabGridScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val tabs = viewModel.tabs
    val extras = LocalRoomExtras.current
    val grid = viewModel.profileSettings().tabLayout ==
        com.roombrowser.domain.model.TabLayout.GRID
    // Which card asked for which sweep. It is held by the SCREEN, not by the
    // card: a dialog composed from inside a lazy grid item dies with the item,
    // and the confirm handler needs the tab id anyway.
    var pendingBulkClose by remember { mutableStateOf<Pair<String, BulkTabClose>?>(null) }
    Column(Modifier.fillMaxSize().background(extras.background)) {
        LibraryTopBar(
            title = "Tabs (${tabs.size})",
            onClose = onClose,
            actions = {
                IconButton(onClick = { viewModel.reopenClosedTab() }) {
                    Icon(Icons.Filled.Restore, contentDescription = "Reopen closed tab")
                }
                TextButton(onClick = { viewModel.startPrivateTab(); onClose() }) { Text("Private") }
                // New tab from the grid: create it AND return to the browser
                // route so the new tab is visibly the active one (staying on
                // the grid made the freshly created tab invisible).
                IconButton(onClick = { viewModel.loadUrl("about:home", newTab = true); onClose() }) {
                    Icon(Icons.Filled.Add, contentDescription = "New tab")
                }
            }
        )
        if (tabs.isEmpty()) {
            CenteredEmptyState("No open tabs", "Tabs are saved per profile and restored after restarts.", Modifier.weight(1f))
        } else if (grid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gridItems(tabs, key = { it.id }) { tab ->
                    TabCard(
                        title = tab.title.ifBlank { tab.url },
                        url = tab.url,
                        isPrivate = tab.isPrivate,
                        isPinned = tab.isPinned,
                        group = tab.groupName,
                        isActive = tab.id == viewModel.activeTabId,
                        onClick = { viewModel.selectTab(tab.id); onClose() },
                        onClose = { viewModel.closeTab(tab.id) },
                        onPin = { viewModel.pinTab(tab.id) },
                        // duplicateTab() duplicates the ACTIVE tab — select
                        // this card's tab first so the menu acts on the card
                        // it was opened from.
                        onDuplicate = { viewModel.selectTab(tab.id); viewModel.duplicateTab() },
                        onCloseOthers = { sweep -> pendingBulkClose = tab.id to sweep },
                        onGroup = { viewModel.groupTab(tab.id, it) }
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listItems(tabs, key = { it.id }) { tab ->
                    // The app's one card radius (RoomCardShape), not a second
                    // 0.9-of-the-theme-radius scale of its own.
                    val rowShape = RoomCardShape
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(rowShape)
                            .background(extras.surface)
                            .border(
                                if (tab.id == viewModel.activeTabId) 1.5.dp else 0.5.dp,
                                if (tab.id == viewModel.activeTabId) extras.primary else extras.border,
                                rowShape
                            )
                            .clickable { viewModel.selectTab(tab.id); onClose() }
                            .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Favicon circle
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(extras.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                tab.title.ifBlank { tab.url }.take(1).uppercase().ifBlank { "\u2022" },
                                style = MaterialTheme.typography.titleMedium,
                                color = extras.primary
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tab.title.ifBlank { tab.url },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                                color = extras.textPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                tab.url,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = extras.textSecondary
                            )
                        }
                        if (tab.isPrivate) {
                            Icon(Icons.Filled.Lock, contentDescription = "Private tab", modifier = Modifier.size(16.dp), tint = extras.primary)
                        }
                        QuietIconButton(
                            contentDescription = "Close tab",
                            icon = Icons.Filled.Close,
                            onClick = { viewModel.closeTab(tab.id) }
                        )
                    }
                }
            }
        }
    }
    pendingBulkClose?.let { (tabId, sweep) ->
        com.roombrowser.main.ui.ConfirmDialog(
            title = sweep.title,
            text = sweep.prompt,
            confirmLabel = sweep.confirmLabel,
            onDismiss = { pendingBulkClose = null },
            onConfirm = {
                // closeOtherTabs() sweeps around the ACTIVE tab, so the card
                // the menu was opened from has to become active first — the
                // same select-then-act shape Duplicate uses above. selectTab
                // assigns activeTabId synchronously, so the sweep that
                // follows already sees the new anchor.
                viewModel.selectTab(tabId)
                viewModel.closeOtherTabs(sweep.onlyLeft)
                pendingBulkClose = null
            }
        )
    }
}

@Composable
private fun TabCard(
    title: String,
    url: String,
    isPrivate: Boolean,
    isPinned: Boolean,
    group: String?,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onPin: () -> Unit,
    onDuplicate: () -> Unit,
    onCloseOthers: (BulkTabClose) -> Unit,
    onGroup: (String?) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val extras = LocalRoomExtras.current
    // Same card radius as the tab list row and every other card surface.
    val shape = RoomCardShape
    Column(
        Modifier
            .clip(shape)
            .background(extras.surface)
            .border(
                if (isActive) 1.5.dp else 0.5.dp,
                if (isActive) extras.primary else extras.border,
                shape
            )
            .clickable(onClick = onClick)
    ) {
        // Preview area: soft gradient placeholder + favicon + status chips
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            extras.primary.copy(alpha = if (isPrivate) 0.30f else 0.16f),
                            extras.surfaceAlt
                        )
                    )
                )
        ) {
            // Favicon circle
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(extras.surface.copy(alpha = 0.88f))
                    .border(0.5.dp, extras.border.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    title.take(1).uppercase().ifBlank { "\u2022" },
                    style = MaterialTheme.typography.titleLarge,
                    color = extras.textPrimary
                )
            }
            if (isPrivate) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(extras.primary.copy(alpha = 0.85f))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(11.dp), tint = extras.onButton)
                    Spacer(Modifier.width(4.dp))
                    Text("Private", style = MaterialTheme.typography.labelSmall, color = extras.onButton)
                }
            }
            if (isPinned) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(extras.surface.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "Pinned tab",
                        tint = extras.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Box(Modifier.align(Alignment.BottomEnd)) {
                // 48dp touch target wrapping a small visual chip. It was 40dp —
                // the same sub-minimum the QuietIconButton in this file was
                // raised from — and a near miss here does not do nothing: it
                // lands on the card, which opens the tab the menu was for.
                Box(
                    Modifier
                        .padding(2.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = { menuOpen = true }),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(extras.background.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Tab actions", modifier = Modifier.size(16.dp), tint = extras.icon)
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isPinned) "Unpin" else "Pin") },
                        onClick = { menuOpen = false; onPin() }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        onClick = { menuOpen = false; onDuplicate() }
                    )
                    // BrowserViewModel.closeOtherTabs() shipped with no caller
                    // at all — these two items are its only entry point, and
                    // without them the only way to clear a crowded switcher
                    // was to tap Close once per card. Both go through a
                    // confirmation (owned by TabGridScreen): a sweep can take
                    // out a dozen tabs and Reopen closed tab restores one.
                    DropdownMenuItem(
                        text = { Text(BulkTabClose.OTHERS.menuLabel) },
                        onClick = { menuOpen = false; onCloseOthers(BulkTabClose.OTHERS) }
                    )
                    DropdownMenuItem(
                        text = { Text(BulkTabClose.LEFT.menuLabel) },
                        onClick = { menuOpen = false; onCloseOthers(BulkTabClose.LEFT) }
                    )
                    DropdownMenuItem(
                        text = { Text("Group: Shopping") },
                        onClick = { menuOpen = false; onGroup("Shopping") }
                    )
                    DropdownMenuItem(
                        text = { Text("Ungroup") },
                        onClick = { menuOpen = false; onGroup(null) }
                    )
                }
            }
        }
        // Info area
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, end = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = extras.textPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textSecondary
                )
            }
            QuietIconButton(
                contentDescription = "Close tab",
                icon = Icons.Filled.Close,
                onClick = onClose
            )
        }
        if (group != null) {
            Text(
                "Group: $group",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = extras.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)
            )
        }
    }
}

/** Bookmarks screen — profile-specific. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val bookmarks = viewModel.bookmarks
    val extras = LocalRoomExtras.current
    Column(Modifier.fillMaxSize().background(extras.background)) {
        LibraryTopBar(title = "Bookmarks", onClose = onClose)
        if (bookmarks.isEmpty()) {
            CenteredEmptyState("No bookmarks", "Bookmarks are stored per profile.", Modifier.weight(1f))
        } else {
            val byFolder = bookmarks.groupBy { it.folder }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                byFolder.forEach { (folder, items) ->
                    if (folder != null) {
                        item(key = "folder-$folder") {
                            SectionHeader(folder)
                        }
                    }
                    listItems(items, key = { it.id }) { bookmark ->
                        LibraryListRow(
                            icon = Icons.Filled.Star,
                            title = bookmark.title.ifBlank { bookmark.url },
                            url = bookmark.url,
                            onClick = { viewModel.loadUrl(bookmark.url); onClose() },
                            onDelete = { viewModel.deleteBookmark(bookmark.id) },
                            deleteContentDescription = "Delete bookmark"
                        )
                    }
                }
            }
        }
    }
}

/** History screen — profile-specific, time-ranged clearing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val history = viewModel.recentHistory
    val extras = LocalRoomExtras.current
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(extras.background)) {
        LibraryTopBar(
            title = "History",
            onClose = onClose,
            actions = {
                TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
            }
        )
        if (history.isEmpty()) {
            CenteredEmptyState("No history", "History is stored per profile and never shared.", Modifier.weight(1f))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listItems(history, key = { it.id }) { entry ->
                    LibraryListRow(
                        icon = Icons.Filled.History,
                        title = entry.title.ifBlank { entry.url },
                        url = entry.url,
                        onClick = { viewModel.loadUrl(entry.url); onClose() },
                        onDelete = { viewModel.deleteHistoryItem(entry.id) },
                        deleteContentDescription = "Delete history item"
                    )
                }
            }
        }
    }
    if (confirmClear) {
        com.roombrowser.main.ui.ConfirmDialog(
            title = "Clear history?",
            text = "Removes the browsing history of this profile.",
            confirmLabel = "Clear",
            onDismiss = { confirmClear = false },
            onConfirm = {
                viewModel.clearHistory(0)
                confirmClear = false
            }
        )
    }
}

/** Bookmarks / history row: rounded card, tinted icon tile, trailing delete. */
@Composable
private fun LibraryListRow(
    icon: ImageVector,
    title: String,
    url: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    deleteContentDescription: String
) {
    val extras = LocalRoomExtras.current
    RoomCard(Modifier.fillMaxWidth(), withGradient = false) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(extras.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = extras.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textSecondary
                )
            }
            QuietIconButton(
                contentDescription = deleteContentDescription,
                icon = Icons.Filled.Delete,
                onClick = onDelete
            )
        }
    }
}

/**
 * Downloads screen.
 *
 * Per-item actions are the contextual ones inline (Pause/Cancel, Resume, Retry,
 * Open/Share) with the rest behind an overflow menu, and tapping a row opens the
 * full record — the twelve columns the list row has no space for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val downloads = viewModel.downloads
    val speeds = viewModel.downloadSpeeds
    val extras = LocalRoomExtras.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var detailsFor by remember { mutableStateOf<Long?>(null) }
    // Both Delete affordances (the row's overflow menu and the details sheet)
    // route through this instead of calling the view model: deleting a
    // download is not "remove it from this list", it is
    // DownloadEngine.delete() unlinking the published file off the device,
    // and it had no confirmation at all behind a menu item sitting one row
    // below "Copy link".
    var confirmDeleteFor by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize().background(extras.background)) {
        LibraryTopBar(title = "Downloads", onClose = onClose)
        if (downloads.isEmpty()) {
            CenteredEmptyState("No downloads", "Downloads are stored per profile in Download/RoomBrowser.", Modifier.weight(1f))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listItems(downloads, key = { it.id }) { download ->
                    DownloadRow(
                        download = download,
                        speed = speeds[download.id],
                        onPause = { viewModel.pauseDownload(download.id) },
                        onResume = { viewModel.resumeDownload(download.id) },
                        onCancel = { viewModel.cancelDownload(download.id) },
                        onRetry = { viewModel.retryDownload(download.id) },
                        onOpen = { viewModel.openDownload(download.id) },
                        onShare = { viewModel.shareDownload(download.id) },
                        onCopyLink = { copyDownloadLink(clipboard, context, download.url) },
                        onDelete = { confirmDeleteFor = download.id },
                        onDetails = { detailsFor = download.id }
                    )
                }
            }
        }
    }

    // Keyed by id rather than holding the entity, so the sheet tracks the live
    // row — a download that finishes while its details are open says so instead
    // of freezing on the state it had when it was tapped.
    val shown = downloads.firstOrNull { it.id == detailsFor }
    if (shown != null) {
        DownloadDetailsSheet(
            download = shown,
            speed = speeds[shown.id],
            onDismiss = { detailsFor = null },
            onCopyLink = { copyDownloadLink(clipboard, context, shown.url) },
            onPause = { viewModel.pauseDownload(shown.id) },
            onResume = { viewModel.resumeDownload(shown.id) },
            onCancel = { viewModel.cancelDownload(shown.id) },
            onRetry = { viewModel.retryDownload(shown.id) },
            onOpen = { viewModel.openDownload(shown.id) },
            onShare = { viewModel.shareDownload(shown.id) },
            // The sheet stays up behind the dialog: cancelling returns the
            // user to the record they were reading, not to the bare list.
            onDelete = { confirmDeleteFor = shown.id }
        )
    }

    // Resolved from the live list for the same reason the sheet is: the row
    // may finish, fail or vanish while the question is on screen.
    val pendingDelete = downloads.firstOrNull { it.id == confirmDeleteFor }
    if (pendingDelete != null) {
        com.roombrowser.main.ui.ConfirmDialog(
            title = "Delete download?",
            text = "\"${pendingDelete.fileName}\" is removed from this device, not just from this list. This cannot be undone.",
            // Deliberately NOT the bare word "Delete": the menu item that
            // opens this dialog is already labelled that, and a distinct
            // confirm label keeps the two addressable apart.
            confirmLabel = "Delete download",
            onDismiss = { confirmDeleteFor = null },
            onConfirm = {
                viewModel.deleteDownload(pendingDelete.id)
                confirmDeleteFor = null
                detailsFor = null
            }
        )
    }
}

private fun copyDownloadLink(
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context,
    url: String
) {
    clipboard.setText(AnnotatedString(url))
    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun DownloadRow(
    download: DownloadEntity,
    speed: Long?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val status = download.status
    val downloaded = download.downloadedBytes
    val total = download.totalBytes
    var menuOpen by remember { mutableStateOf(false) }

    RoomCard(Modifier.fillMaxWidth(), withGradient = false) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(extras.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = extras.primary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    download.fileName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onDetails() }
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Download options", tint = extras.textSecondary)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Details") },
                            onClick = { menuOpen = false; onDetails() }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy link") },
                            onClick = { menuOpen = false; onCopyLink() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }
            if (status == DownloadStatus.RUNNING.name) {
                val percent = DownloadFormat.percent(downloaded, total)
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp)
                    )
                }
            }
            Text(
                statusLine(download, speed),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (status == DownloadStatus.FAILED.name) MaterialTheme.colorScheme.error else extras.textSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (status) {
                    DownloadStatus.RUNNING.name, DownloadStatus.QUEUED.name -> {
                        TextButton(onClick = onPause) { Text("Pause") }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    DownloadStatus.PAUSED.name -> {
                        TextButton(onClick = onResume) { Text("Resume") }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    DownloadStatus.FAILED.name, DownloadStatus.CANCELLED.name -> {
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                    DownloadStatus.COMPLETED.name -> {
                        TextButton(onClick = onOpen) { Text("Open") }
                        TextButton(onClick = onShare) { Text("Share") }
                    }
                }
            }
        }
    }
}

/**
 * One line of state for a row: what happened, how far along, how fast, and how
 * much longer — omitting whatever is not knowable rather than printing a zero.
 */
private fun statusLine(download: DownloadEntity, speed: Long?): String {
    val downloaded = download.downloadedBytes
    val total = download.totalBytes
    return when (download.status) {
        DownloadStatus.QUEUED.name -> "Queued"
        DownloadStatus.RUNNING.name -> {
            val parts = mutableListOf(sizeLine(downloaded, total))
            DownloadFormat.percent(downloaded, total)?.let { parts.add("$it%") }
            DownloadFormat.speed(speed ?: 0)?.let { parts.add(it) }
            DownloadFormat.etaSeconds(downloaded, total, speed ?: 0)
                ?.let { DownloadFormat.eta(it) }
                ?.let { parts.add("$it left") }
            parts.joinToString(" · ")
        }
        DownloadStatus.PAUSED.name -> "Paused · " + sizeLine(downloaded, total)
        DownloadStatus.COMPLETED.name -> "Completed · " + DownloadFormat.bytes(
            if (total > 0) total else downloaded
        )
        DownloadStatus.FAILED.name -> "Failed" + (download.error?.let { ": $it" } ?: "")
        DownloadStatus.CANCELLED.name -> if (downloaded > 0) {
            "Cancelled · " + sizeLine(downloaded, total)
        } else {
            "Cancelled"
        }
        else -> download.status
    }
}

private fun sizeLine(downloaded: Long, total: Long): String =
    if (total > 0) "${DownloadFormat.bytes(downloaded)} / ${DownloadFormat.bytes(total)}"
    else "${DownloadFormat.bytes(downloaded)} downloaded"

/** The full record for one download — every column the list row cannot show. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadDetailsSheet(
    download: DownloadEntity,
    speed: Long?,
    onDismiss: () -> Unit,
    onCopyLink: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val downloaded = download.downloadedBytes
    val total = download.totalBytes
    val percent = DownloadFormat.percent(downloaded, total)

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .fillMaxWidth()
                // Padding sits OUTSIDE the scroll, like every other sheet, so
                // the gutters stay fixed while the body scrolls.
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader(download.fileName)
            Text(
                DownloadStatusLabels.of(download.status),
                style = MaterialTheme.typography.labelLarge,
                color = if (download.status == DownloadStatus.FAILED.name)
                    MaterialTheme.colorScheme.error else extras.primary
            )

            if (download.status == DownloadStatus.RUNNING.name) {
                val eta = DownloadFormat.etaSeconds(downloaded, total, speed ?: 0)
                Spacer(Modifier.height(12.dp))
                if (percent != null) {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (eta != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${DownloadFormat.eta(eta)} remaining at ${DownloadFormat.speed(speed ?: 0) ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            DetailRow("Size", if (total > 0) DownloadFormat.bytes(total) else "Unknown")
            DetailRow("Downloaded", DownloadFormat.bytes(downloaded))
            DetailRow("Progress", percent?.let { "$it%" } ?: "Unknown")
            if (download.status == DownloadStatus.RUNNING.name) {
                DetailRow("Speed", DownloadFormat.speed(speed ?: 0) ?: "—")
            }
            DetailRow("Type", download.mimeType)
            DetailRow("Link", download.url, selectable = true)
            if (download.destination.isNotBlank()) {
                DetailRow("Saved to", download.destination, selectable = true)
            }
            DetailRow("Started", formatTimestamp(download.createdAt) ?: "Unknown")
            formatTimestamp(download.completedAt)?.let { DetailRow("Completed", it) }
            // takeIf rather than a null check: `error` is a public property of a
            // class in another module, so the compiler will not smart-cast it.
            download.error?.takeIf { it.isNotBlank() }?.let { DetailRow("Error", it) }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (download.status) {
                    DownloadStatus.RUNNING.name, DownloadStatus.QUEUED.name -> {
                        Button(onClick = onPause) { Text("Pause") }
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                    DownloadStatus.PAUSED.name -> {
                        Button(onClick = onResume) { Text("Resume") }
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                    DownloadStatus.FAILED.name, DownloadStatus.CANCELLED.name -> {
                        Button(onClick = onRetry) { Text("Retry") }
                    }
                    DownloadStatus.COMPLETED.name -> {
                        Button(onClick = onOpen) { Text("Open") }
                        OutlinedButton(onClick = onShare) { Text("Share") }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onCopyLink) { Text("Copy link") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One label/value line of the details sheet.
 *
 * [selectable] wraps the value in a `SelectionContainer` so a long link or a
 * long file path can be dragged out by hand — the sheet's own "Copy link"
 * button copies the whole URL, which is not what you want when the interesting
 * part is in the middle of it.
 */
@Composable
private fun DetailRow(label: String, value: String, selectable: Boolean = false) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(12.dp))
        if (selectable) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textPrimary
                )
            }
        } else {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = extras.textPrimary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** A timestamp the user can read, or null when there is no time to show. */
private fun formatTimestamp(millis: Long?): String? {
    if (millis == null || millis <= 0L) return null
    return java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(millis))
}

/** Status names as prose. */
private object DownloadStatusLabels {
    fun of(status: String): String = when (status) {
        DownloadStatus.QUEUED.name -> "Queued"
        DownloadStatus.RUNNING.name -> "Downloading"
        DownloadStatus.PAUSED.name -> "Paused"
        DownloadStatus.COMPLETED.name -> "Completed"
        DownloadStatus.FAILED.name -> "Failed"
        DownloadStatus.CANCELLED.name -> "Cancelled"
        else -> status
    }
}

/** Privacy Dashboard (spec section 9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDashboardScreen(viewModel: BrowserViewModel, onClose: () -> Unit) {
    val stats = viewModel.privacyStats
    val extras = LocalRoomExtras.current
    Column(Modifier.fillMaxSize().background(extras.background)) {
        LibraryTopBar(title = "Privacy Dashboard", onClose = onClose)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatTile(
                    value = ((stats[StatCategories.TRACKER] ?: 0) + (stats[StatCategories.CROSS_SITE_TRACKER] ?: 0)).toString(),
                    label = "Trackers blocked",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = (stats[StatCategories.AD] ?: 0).toString(),
                    label = "Ads blocked",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatTile(
                    value = (stats[StatCategories.HTTPS_UPGRADE] ?: 0).toString(),
                    label = "HTTPS upgrades",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = (stats[StatCategories.POPUP] ?: 0).toString(),
                    label = "Popups blocked",
                    modifier = Modifier.weight(1f)
                )
            }
            RoomCard(Modifier.fillMaxWidth(), withGradient = false) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Connection",
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.primary
                    )
                    val dns = viewModel.dnsState
                    Text(
                        when (dns) {
                            is com.roombrowser.browser.engine.DnsMonitor.DnsState.System ->
                                "DNS: System default (app connections)"
                            is com.roombrowser.browser.engine.DnsMonitor.DnsState.Protected ->
                                "DNS: Protected (${dns.protocol}) — ${dns.resolver}"
                            is com.roombrowser.browser.engine.DnsMonitor.DnsState.NotEnforced ->
                                "DNS: ${dns.protocol} validated, not enforced by this app — enable OS Private DNS (${dns.detail})"
                            is com.roombrowser.browser.engine.DnsMonitor.DnsState.Misconfigured ->
                                "DNS: Check configuration (${dns.reason})"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textPrimary
                    )
                    Text(
                        "HTTPS upgrades: ${if (viewModel.profileSettings().httpsUpgrade) "Enabled" else "Disabled"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textSecondary
                    )
                }
            }
            Text(
                "All statistics on this dashboard come from real blocking events recorded by this browser — nothing is estimated or faked.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
