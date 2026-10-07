package com.roombrowser.browser.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.PageError
import com.roombrowser.browser.StatCategories
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.ui.common.LoadingBar
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCard
import com.roombrowser.ui.common.RoomCardShape
import com.roombrowser.ui.common.StatTile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main browser content: elegant pill omnibox, WebView host, modern homepage
 * and honest error pages. Chrome colors come from the per-profile theme
 * (LocalRoomExtras) so the whole surface restyles with the active theme.
 */
@Composable
fun BrowserContent(
    viewModel: BrowserViewModel,
    onShowShields: () -> Unit,
    onOpenPrivacyDashboard: () -> Unit
) {
    val page = viewModel.pageState
    val extras = LocalRoomExtras.current
    // A TextFieldValue, not a String: the selection has to survive the
    // double-tap below, and only an explicit range can express "all of it".
    var omniInput by remember(page.url) {
        mutableStateOf(TextFieldValue(if (page.isHomepage) "" else UrlIntelligence.displayUrl(page.url)))
    }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        // ---------- Omnibox row (floating pill) ---------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The address pill owns the FULL row width — back/forward moved
            // down to the bottom navigation bar (Brave-style) so the URL
            // never fights nav arrows for space.
            //
            // The pill itself is deliberately NOT clickable. Tapping the
            // address bar has to focus the URL field — that is the only way
            // a user can type a URL, and it is what every other browser
            // does. Site controls live on the leading lock/shield icon.
            // (With a clickable pill the whole bar was one Shields button:
            // tap-to-edit did nothing but open the site-controls sheet, and
            // any UiAutomator tap helper that resolves the field by walking
            // up to its nearest clickable ancestor — `clickSmart` does — hit
            // that button instead of the field, leaving the sheet over the
            // page for the rest of the test.)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(extras.radius.dp))
                    .background(extras.addressBar)
                    .border(0.5.dp, extras.border, RoundedCornerShape(extras.radius.dp))
                    .heightIn(min = 46.dp)
                    // No vertical padding: the 48dp site-controls target
                    // below now sets this pill's height. The 11.dp that used
                    // to be here existed only to pad a 24dp icon box out to
                    // the 46dp pill, and keeping both would have made the
                    // pill 70dp tall. The row around it is 48dp either way
                    // (the reload/bookmark IconButtons are 48dp), so the
                    // omnibox chrome does not change height at all.
                    .padding(horizontal = 12.dp)
                    .semantics { contentDescription = "Address bar: ${if (page.isHomepage) "search or type URL" else page.url}" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Site controls (shields, connection info, per-site
                // toggles) — the lock is the trigger, as in every browser.
                //
                // 48dp, not the 24dp it used to be: this is the ONLY way to
                // the shields sheet, and a 24dp target is half the 48dp
                // minimum Material and the accessibility scanner both ask
                // for — it took a precise tap, and a near miss landed on the
                // URL field instead and opened the keyboard. Only the TARGET
                // grew; the glyph is still 17dp, so it sits 12dp further
                // from the pill's edge now that it is centred in a real
                // button footprint.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onShowShields)
                        .semantics { contentDescription = "Site controls" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (page.isPrivate) Icons.Filled.Close else if (page.secure) Icons.Filled.Lock else Icons.Filled.Security,
                        contentDescription = null,
                        tint = if (page.secure || page.isPrivate) extras.primary else extras.icon,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                val omniFocus = remember { FocusRequester() }
                // The pointerInput below is keyed on Unit, so it reads the
                // action through rememberUpdatedState rather than capturing
                // the field from the first composition.
                val selectAllText = rememberUpdatedState { omniInput = selectAllIn(omniInput) }
                Box(
                    Modifier
                        .weight(1f)
                        // Not clickable/detectTapGestures: the field's own
                        // tap handler is nested inside and consumes the down
                        // first, which would starve either. This reads raw
                        // changes and consumes nothing.
                        .pointerInput(Unit) {
                            detectDoubleTap { selectAllText.value() }
                        }
                        // Tap anywhere on the bar to edit. No ripple
                        // indication: the caret is the feedback, and a
                        // ripple would paint over the URL.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { omniFocus.requestFocus() }
                        // Stable a11y hook for the navigation e2e suite
                        // (same pattern as agent_composer_field). It sits on
                        // the field's own container so the node a tap helper
                        // resolves is the node that focuses the field.
                        .semantics { contentDescription = "omni_field" }
                ) {
                    if (omniInput.text.isEmpty()) {
                        Text(
                            if (page.isPrivate) "Private tab — search or type URL" else "Search or type URL",
                            style = MaterialTheme.typography.bodyMedium,
                            color = extras.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = omniInput,
                        onValueChange = { omniInput = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = extras.textPrimary),
                        cursorBrush = SolidColor(extras.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = { viewModel.onOmniBoxInput(omniInput.text) }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(omniFocus)
                    )
                }
                if (!page.isHomepage) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = extras.icon.copy(alpha = 0.55f),
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            // Reload / Stop — same dual role as the bottom-bar refresh
            // slot: Stop while loading, Reload otherwise; nothing to do on
            // the start page so it greys out there.
            IconButton(
                onClick = { if (page.loading) viewModel.stopLoading() else viewModel.reload() },
                enabled = !page.isHomepage,
                modifier = Modifier.semantics {
                    contentDescription = if (page.loading) "Stop loading" else "Reload"
                }
            ) {
                Icon(
                    if (page.loading) Icons.Filled.Close else Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = if (page.isHomepage) extras.icon.copy(alpha = 0.35f) else extras.icon
                )
            }
            AnimatedVisibility(visible = !page.isHomepage) {
                IconButton(
                    onClick = { viewModel.toggleBookmark() },
                    modifier = Modifier.semantics { contentDescription = "Bookmark this page" }
                ) {
                    Icon(
                        if (viewModel.bookmarks.any { it.url == page.url }) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = null,
                        tint = if (viewModel.bookmarks.any { it.url == page.url }) extras.primary else extras.icon
                    )
                }
            }
        }

        AnimatedVisibility(visible = page.loading, enter = fadeIn(), exit = fadeOut()) {
            LoadingBar(visible = true, progress = page.progress)
        }

        // ---------- Page / homepage / error --------------------------------
        Box(
            Modifier
                .weight(1f)
                .background(extras.background)
        ) {
            val error = viewModel.pageError
            // Which surface is on screen and why — the one line that says
            // whether a missing page is a blank engine, a stale error, or the
            // start page, and which ViewModel decided it. Logged on CHANGE
            // only, so it costs one line per screen transition.
            LaunchedEffect(error, page.url, page.isHomepage, page.loading) {
                android.util.Log.d(
                    "RoomNav",
                    "vm=${viewModel.navId} surface=${when {
                        error != null -> "error:${error::class.simpleName}@${error.urlOrNull()}"
                        page.isHomepage -> "home"
                        else -> "engine"
                    }} url=${page.url} loading=${page.loading}"
                )
            }
            when {
                error != null -> ErrorPage(
                    error = error,
                    onRetry = { viewModel.reload() }
                )
                page.isHomepage -> Homepage(
                    viewModel = viewModel,
                    onOpenPrivacyDashboard = onOpenPrivacyDashboard,
                    onOmniSubmit = { viewModel.onOmniBoxInput(it) }
                )
                else -> EngineViewHost(viewModel = viewModel)
            }
        }
    }
}

/**
 * Calls [onDoubleTap] on the second UP of two taps in the view's double-tap
 * window and slop. Nothing is consumed, and firing on the up (after the
 * field has placed its caret) is what makes the selection stick.
 */
private suspend fun PointerInputScope.detectDoubleTap(onDoubleTap: () -> Unit) {
    awaitPointerEventScope {
        var lastDownTime = 0L
        var lastDownPosition = Offset.Zero
        var secondDownTime = 0L
        while (true) {
            val event = awaitPointerEvent()
            for (change in event.changes) {
                if (change.changedToDownIgnoreConsumed()) {
                    val isSecondTap = lastDownTime != 0L &&
                        change.uptimeMillis - lastDownTime <= viewConfiguration.doubleTapTimeoutMillis &&
                        (change.position - lastDownPosition).getDistance() <= viewConfiguration.touchSlop
                    lastDownTime = change.uptimeMillis
                    lastDownPosition = change.position
                    secondDownTime = if (isSecondTap) change.uptimeMillis else 0L
                } else if (
                    secondDownTime != 0L &&
                    change.changedToUpIgnoreConsumed() &&
                    change.uptimeMillis - secondDownTime <= viewConfiguration.longPressTimeoutMillis
                ) {
                    secondDownTime = 0L
                    onDoubleTap()
                }
            }
        }
    }
}

/** Error pages (spec section 52): No Internet / SSL / DNS / generic. */
@Composable
private fun ErrorPage(error: PageError, onRetry: () -> Unit) {
    val context = LocalContext.current
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val (title, message) = when (error) {
            is PageError.NoInternet ->
                "No Internet" to "This device is not online. Check Wi-Fi or mobile data, then try again."
            is PageError.Unreachable ->
                "Site Unreachable" to "Your connection is working, but this site did not answer. It may be down right now, or this network may be blocking it."
            is PageError.Ssl ->
                "Connection Not Secure" to (
                    error.message +
                        " An expired certificate, a wrong device date and time, or a public" +
                        " Wi-Fi sign-in page in the way all look like this. Room Browser will" +
                        " not bypass it for you."
                    )
            is PageError.DnsFailure ->
                "DNS Resolution Failed" to "Your connection is working, but this site's address could not be resolved. A private DNS setting or a Wi-Fi sign-in page may be in the way."
            is PageError.Generic -> "Page Problem" to (error.message ?: "The page could not be loaded.")
        }
        Box(
            Modifier
                .size(64.dp)
                .clip(RoomCardShape)
                .background(extras.surfaceAlt),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = extras.primary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = extras.textPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Try Again") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            runCatching { context.openWirelessSettings() }
        }) { Text("Network Settings") }
    }
}

private fun android.content.Context.openWirelessSettings() {
    startActivity(
        android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
private fun EngineViewHost(viewModel: BrowserViewModel) {
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { frame ->
            // Swap-in semantics for the ACTIVE tab's engine. Attaching and
            // detaching is the ENGINE's business — GeckoView requires its
            // session to be released before another is attached, and a bare
            // addView/removeView cannot express that — so the host hands the
            // swap to the ViewModel, which goes through the facade. This runs
            // on EVERY engine swap (activeSession is Compose state), even when
            // pageState alone would not have recomposed, and it also fires the
            // navigation/restore queued while the engine had no parent (see
            // BrowserViewModel.runWhenAttached).
            viewModel.attachActiveSessionTo(frame)
        },
        onRelease = { frame ->
            viewModel.detachAttachedSession()
            frame.removeAllViews()
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun Homepage(
    viewModel: BrowserViewModel,
    onOpenPrivacyDashboard: () -> Unit,
    onOmniSubmit: (String) -> Unit
) {
    val settings = viewModel.profileSettings()
    val extras = LocalRoomExtras.current
    val greeting = remember {
        val hour = SimpleDateFormat("HH", Locale.getDefault()).format(Date()).toInt()
        when {
            hour < 5 -> "Good night"
            hour < 12 -> "Good morning"
            hour < 18 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (settings.showClock) {
            Text(
                greeting,
                style = MaterialTheme.typography.headlineSmall,
                color = extras.textPrimary
            )
            Spacer(Modifier.height(20.dp))
        }
        if (viewModel.pageState.isPrivate) {
            RoomCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Shield,
                            contentDescription = null,
                            tint = extras.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Private tab", style = MaterialTheme.typography.titleMedium, color = extras.textPrimary)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No browsing history will be saved for this tab. Session cookies are cleared when private tabs close (subject to platform limitations — see PRIVACY.md).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textSecondary
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (settings.showPrivacyStats) {
            val stats = viewModel.privacyStats
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    value = (stats[StatCategories.AD] ?: 0).toString(),
                    label = "ads blocked",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = ((stats[StatCategories.TRACKER] ?: 0) + (stats[StatCategories.CROSS_SITE_TRACKER] ?: 0)).toString(),
                    label = "trackers blocked",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = (stats[StatCategories.HTTPS_UPGRADE] ?: 0).toString(),
                    label = "HTTPS upgrades",
                    modifier = Modifier.weight(1f)
                )
            }
            TextButton(onClick = onOpenPrivacyDashboard) { Text("Privacy Dashboard") }
            Spacer(Modifier.height(20.dp))
        }

        if (settings.homepageShortcuts.isNotEmpty()) {
            Text(
                "Quick Access",
                style = MaterialTheme.typography.labelMedium,
                color = extras.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                settings.homepageShortcuts.take(4).forEach { url ->
                    val host = UrlIntelligence.hostOf(url) ?: url
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoomCardShape)
                            .clickable { viewModel.loadUrl(url) }
                            .padding(6.dp)
                    ) {
                        Box(
                            Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(extras.surfaceAlt)
                                .border(0.5.dp, extras.border, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                host.substringBefore('.').take(1).uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                color = extras.primary
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            host.substringBefore('.'),
                            style = MaterialTheme.typography.labelMedium,
                            color = extras.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (settings.showRecentSites && viewModel.recentHistory.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Recent",
                style = MaterialTheme.typography.labelMedium,
                color = extras.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            )
            viewModel.recentHistory.take(5).forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        // The one card radius, like every other row surface in
                        // the app — this was a hardcoded 14dp, the odd one out
                        // against the library rows and the quick-access tiles.
                        .clip(RoomCardShape)
                        .clickable { viewModel.loadUrl(item.url) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(extras.surfaceAlt),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            (item.title.ifBlank { item.url }).take(1).uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = extras.primary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        item.title.ifBlank { item.url },
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * The URL a page error was raised FOR.
 *
 * The error surface renders ABOVE the page surface, so whose failure it is
 * decides whether the screen is telling the truth: an error whose URL is not
 * the active tab's belongs to a tab no longer in front (see
 * BrowserViewModel.openNewTab/selectTab, which clear it).
 */
private fun PageError.urlOrNull(): String = when (this) {
    is PageError.NoInternet -> url
    is PageError.Unreachable -> url
    is PageError.Ssl -> url
    is PageError.DnsFailure -> url
    is PageError.Generic -> url
}
