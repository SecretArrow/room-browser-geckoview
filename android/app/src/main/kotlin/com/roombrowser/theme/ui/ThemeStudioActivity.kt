@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.roombrowser.theme.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.domain.theme.GradientDirection
import com.roombrowser.domain.theme.GradientStyle
import com.roombrowser.domain.theme.RoomThemeMode
import com.roombrowser.domain.theme.RoomThemeSpec
import com.roombrowser.theme.ThemeStudioController
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomPreviewTheme
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.SettingSwitchRow

/**
 * Theme Studio — its OWN activity (own window / back stack / IME handling).
 *
 * Per-profile theme editor with live preview:
 *  - 18 hand-tuned built-in presets + the user's saved custom themes
 *  - mode: Light / Dark / AMOLED / Auto (follows system appearance)
 *  - full palette editor (background, surface, accents, text, address bar,
 *    tab bar, navigation bar, button, border, icon, selection colors)
 *  - gradient style + direction, corner radius, transparency, blur, contrast
 *  - save / duplicate / rename / reset / import / export as JSON
 *
 * Nothing is written to the profile until "Apply" — tapping a preset only
 * previews it here. The ':browser' process re-reads the profile (Room
 * multi-instance invalidation) and re-themes itself live.
 */
class ThemeStudioActivity : ComponentActivity() {

    private lateinit var controller: ThemeStudioController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)?.let { ProfileId(it) }
        controller = ThemeStudioController(application, profileId)
        controller.start()
        setContent {
            RoomBrowserTheme {
                ThemeStudioRoot(controller = controller, onDone = { finish() })
            }
        }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        fun launch(context: android.content.Context, profileId: String) {
            context.startActivity(
                Intent(context, ThemeStudioActivity::class.java).apply {
                    putExtra(EXTRA_PROFILE_ID, profileId)
                }
            )
        }
    }
}

@Composable
private fun ThemeStudioRoot(controller: ThemeStudioController, onDone: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message = controller.message
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            controller.clearMessage()
        }
    }

    // Dialog states
    var saveAsOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RoomThemeSpec?>(null) }
    var importOpen by remember { mutableStateOf(false) }
    // Reset is the one quick action that THROWS WORK AWAY: resetToDefault()
    // replaces the working spec with Obsidian and clears the profile's stored
    // theme_json, so every color, gradient, radius and blur the user tuned is
    // gone and there is no undo. It sat one tap away, right beside Apply, with
    // no question asked — hence this gate.
    var confirmReset by remember { mutableStateOf(false) }
    var pickerField by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Padded above the system navigation bar. contentWindowInsets is
            // zeroed on this Scaffold, so a bare SnackbarHost would draw UNDER
            // the Back/Home/Recents bar.
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom)
                )
            )
        },
        topBar = {
            TopAppBar(
                title = { Text("Theme Studio") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
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
                .verticalScroll(rememberScrollState())
                .padding(bottom = 130.dp)
        ) {
            // ---------- Live preview + quick actions ------------------------
            LivePreview(spec = controller.working)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { controller.applyToProfile() },
                    modifier = Modifier.weight(1f),
                    enabled = controller.profile != null
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Apply", maxLines = 1)
                }
                OutlinedButton(onClick = { confirmReset = true }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reset")
                }
            }
            Text(
                if (controller.profile != null)
                    "Previewing for profile \"${controller.profile!!.name}\" — Apply writes this theme to that profile only. Other profiles are never affected."
                else
                    "Open the studio from a profile to apply themes to it.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalRoomExtras.current.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // ---------- Mode -------------------------------------------------
            SectionHeader("Theme mode")
            var modeMenuOpen by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = modeMenuOpen,
                onExpandedChange = { modeMenuOpen = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                OutlinedTextField(
                    value = when (controller.working.mode) {
                        RoomThemeMode.LIGHT -> "Light"
                        RoomThemeMode.DARK -> "Dark"
                        RoomThemeMode.AMOLED -> "AMOLED"
                        RoomThemeMode.AUTO -> "Auto"
                    },
                    onValueChange = { },
                    readOnly = true,
                    singleLine = true,
                    label = { Text("Mode") },
                    shape = RoundedCornerShape((LocalRoomExtras.current.radius * 0.6f).dp),
                    trailingIcon = {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.rotate(if (modeMenuOpen) 180f else 0f)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = modeMenuOpen,
                    onDismissRequest = { modeMenuOpen = false }
                ) {
                    RoomThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when (mode) {
                                        RoomThemeMode.LIGHT -> "Light"
                                        RoomThemeMode.DARK -> "Dark"
                                        RoomThemeMode.AMOLED -> "AMOLED"
                                        RoomThemeMode.AUTO -> "Auto"
                                    }
                                )
                            },
                            onClick = {
                                controller.update { it.copy(mode = mode) }
                                modeMenuOpen = false
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "theme_mode_${mode.name.lowercase()}"
                            }
                        )
                    }
                }
            }
            Text(
                "Auto follows your system's light / dark appearance.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalRoomExtras.current.textSecondary,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp)
            )

            // ---------- Built-in presets + custom gallery -------------------
            SectionHeader("Presets")
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BuiltInThemes.all.forEach { preset ->
                    ThemeCard(
                        spec = preset,
                        selected = controller.working.id == preset.id,
                        onClick = { controller.load(preset) }
                    )
                }
            }
            if (controller.gallery.isNotEmpty()) {
                SectionHeader("My themes")
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    controller.gallery.forEach { custom ->
                        ThemeCard(
                            spec = custom,
                            selected = controller.working.id == custom.id,
                            onClick = { controller.load(custom) },
                            isCustom = true,
                            onRename = { renameTarget = custom },
                            onDuplicate = { controller.duplicateTheme(custom.id) },
                            onDelete = { controller.deleteTheme(custom.id) }
                        )
                    }
                }
            }

            // ---------- Palette editor --------------------------------------
            PaletteEditor(controller = controller, pickerField = pickerField, onPickerField = { pickerField = it })

            // ---------- Gradient --------------------------------------------
            SectionHeader("Gradient")
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GradientStyle.entries.forEach { style ->
                    FilterChip(
                        selected = controller.working.gradientStyle == style,
                        onClick = { controller.update { it.copy(gradientStyle = style) } },
                        label = {
                            Text(
                                when (style) {
                                    GradientStyle.NONE -> "None"
                                    GradientStyle.LINEAR -> "Linear"
                                    GradientStyle.RADIAL -> "Radial"
                                }
                            )
                        }
                    )
                }
            }
            if (controller.working.gradientStyle == GradientStyle.LINEAR) {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GradientDirection.entries.forEach { dir ->
                        FilterChip(
                            selected = controller.working.gradientDirection == dir,
                            onClick = { controller.update { it.copy(gradientDirection = dir) } },
                            label = { Text(directionLabel(dir)) }
                        )
                    }
                }
            }

            // ---------- Shape & feel sliders --------------------------------
            SectionHeader("Shape & feel")
            SliderRow(
                label = "Corner radius",
                value = controller.working.cornerRadius.toFloat(),
                range = 4f..32f,
                steps = 27,
                suffix = " dp",
                onValueChange = { v -> controller.update { it.copy(cornerRadius = v.toInt()) } }
            )
            SliderRow(
                label = "UI transparency",
                value = controller.working.transparency.toFloat(),
                range = 0f..90f,
                steps = 17,
                suffix = " %",
                onValueChange = { v -> controller.update { it.copy(transparency = v.toInt()) } }
            )
            SliderRow(
                label = "Blur intensity (glass)",
                value = controller.working.blur.toFloat(),
                range = 0f..100f,
                steps = 19,
                suffix = " %",
                onValueChange = { v -> controller.update { it.copy(blur = v.toInt()) } }
            )
            SliderRow(
                label = "Contrast level",
                value = controller.working.contrast.toFloat(),
                range = 50f..150f,
                steps = 19,
                suffix = " %",
                onValueChange = { v -> controller.update { it.copy(contrast = v.toInt()) } }
            )
            SettingSwitchRow(
                title = "Reduce motion",
                subtitle = "Removes the page-load progress animation. Material's ripple, the " +
                    "system's animation scale and the rest of the interface are unaffected.",
                checked = controller.working.reducedMotion,
                onCheckedChange = { v -> controller.update { it.copy(reducedMotion = v) } }
            )

            // ---------- Save / import / export ------------------------------
            SectionHeader("Save & share")
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { saveAsOpen = true }) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save as…")
                }
                OutlinedButton(onClick = { importOpen = true }) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import")
                }
            }
            val clipboard = LocalClipboardManager.current
            val context = LocalContext.current
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(controller.exportJson()))
                        controller.toast("Theme JSON copied to clipboard")
                    }
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export JSON")
                }
                OutlinedButton(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_TEXT, controller.exportJson())
                        }
                        context.startActivity(Intent.createChooser(share, "Share theme"))
                    }
                ) {
                    Icon(Icons.Filled.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Floating Apply — always reachable without scrolling. INSET FIX:
        // the FAB must sit ABOVE the system navigation bar (3-button
        // Back/Home/Recents or the gesture hint) and beside display cutouts —
        // a bare 24dp bottom padding put it INSIDE the nav-bar zone on
        // 3-button devices, where taps fought the system buttons.
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            ExtendedFloatingActionButton(
                onClick = { controller.applyToProfile() },
                icon = { Icon(Icons.Filled.Palette, contentDescription = null) },
                text = { Text("Apply to profile") },
                containerColor = LocalRoomExtras.current.primary,
                contentColor = LocalRoomExtras.current.onButton,
                modifier = Modifier.semantics { contentDescription = "theme_apply" }
            )
        }
    }

    // ---------------- dialogs ----------------

    if (saveAsOpen) {
        NameDialog(
            title = "Save theme as…",
            initial = "${controller.working.name} custom",
            confirmLabel = "Save",
            onDismiss = { saveAsOpen = false },
            onConfirm = { name ->
                controller.saveToGallery(name)
                saveAsOpen = false
            }
        )
    }

    renameTarget?.let { target ->
        NameDialog(
            title = "Rename theme",
            initial = target.name,
            confirmLabel = "Rename",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                controller.renameTheme(target.id, name)
                renameTarget = null
            }
        )
    }

    if (importOpen) {
        ImportThemeDialog(
            onDismiss = { importOpen = false },
            onImport = { raw ->
                if (!controller.importJson(raw)) {
                    controller.toast("That text is not a valid Room Browser theme")
                }
                importOpen = false
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset this theme?") },
            text = {
                Text(
                    if (controller.profile != null)
                        "Every color, gradient, corner radius and blur you tuned goes back to " +
                            "the built-in Obsidian theme, and profile \"${controller.profile!!.name}\" " +
                            "stops using its saved theme. Themes you kept in My themes are not touched."
                    else
                        "Every color, gradient, corner radius and blur you tuned goes back to the " +
                            "built-in Obsidian theme. Themes you kept in My themes are not touched.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            // "Reset theme", not a bare "Reset": the quick-action button is
            // already labelled "Reset", and two nodes answering to the same
            // text would make a tap on either ambiguous.
            confirmButton = {
                TextButton(onClick = {
                    controller.resetToDefault()
                    confirmReset = false
                }) { Text("Reset theme") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------------------
// Live preview — a miniature Room Browser rendered with the WORKING spec
// ---------------------------------------------------------------------------

@Composable
private fun LivePreview(spec: RoomThemeSpec) {
    val outer = LocalRoomExtras.current
    RoomPreviewTheme(spec = spec) {
        val extras = LocalRoomExtras.current
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(230.dp)
                .clip(RoundedCornerShape(extras.radius.dp))
                .background(extras.background)
                .border(0.5.dp, outer.border, RoundedCornerShape(extras.radius.dp))
        ) {
            // mock address bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(extras.radius.dp))
                    .background(extras.addressBar)
                    .border(0.5.dp, extras.border, RoundedCornerShape(extras.radius.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(extras.primary))
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(extras.textSecondary.copy(alpha = 0.4f))
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(extras.secondary))
            }
            // mock page content
            Column(Modifier.padding(horizontal = 14.dp)) {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .padding(vertical = 4.dp)
                            .fillMaxWidth(if (i == 2) 0.6f else 0.92f)
                            .height(9.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(extras.textPrimary.copy(alpha = 0.25f))
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
                                .background(extras.surfaceAlt)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape((extras.radius * 0.6f).dp))
                        .background(extras.selection)
                )
            }
            Spacer(Modifier.weight(1f))
            // mock bottom bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(extras.radius.dp))
                    .background(extras.navBar)
                    .border(0.5.dp, extras.border, RoundedCornerShape(extras.radius.dp))
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(5) { i ->
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (i == 2) extras.primary else extras.icon.copy(alpha = 0.6f))
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Theme cards (presets + custom gallery entries)
// ---------------------------------------------------------------------------

@Composable
private fun ThemeCard(
    spec: RoomThemeSpec,
    selected: Boolean,
    onClick: () -> Unit,
    isCustom: Boolean = false,
    onRename: () -> Unit = {},
    onDuplicate: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    val extras = LocalRoomExtras.current
    var menuOpen by remember { mutableStateOf(false) }
    val width = 104.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width, 76.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                // Addressable + clickable on the SAME node (the proven
                // gear-button pattern; UI tests target this description).
                .semantics { contentDescription = "theme_card_${spec.id}" }
        ) {
            // dual palette strip: dark top / light bottom
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).background(Color(spec.dark.background))) {
                    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(spec.dark.primary)))
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(spec.dark.secondary)))
                    }
                }
                Box(Modifier.weight(1f).background(Color(spec.light.background))) {
                    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(spec.light.primary)))
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(spec.light.secondary)))
                    }
                }
            }
            Box(
                Modifier
                    .matchParentSize()
                    .border(
                        width = if (selected) 2.dp else 0.5.dp,
                        color = if (selected) extras.primary else extras.border,
                        shape = RoundedCornerShape(18.dp)
                    )
            )
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(extras.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = extras.onButton,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            if (isCustom) {
                Box(Modifier.align(Alignment.BottomEnd)) {
                    // 40dp visual = at least a 40dp touch target (the old 26dp
                    // chip was unreachable for anyone with normal fingers).
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier
                            .size(40.dp)
                            .background(extras.background.copy(alpha = 0.55f), CircleShape)
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "Custom theme actions", modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { menuOpen = false; onRename() }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            onClick = { menuOpen = false; onDuplicate() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            spec.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) extras.primary else extras.textPrimary,
            modifier = Modifier.width(width)
        )
    }
}

// ---------------------------------------------------------------------------
// Palette editor (edits the palette that is currently ACTIVE)
// ---------------------------------------------------------------------------

@Composable
private fun PaletteEditor(
    controller: ThemeStudioController,
    pickerField: String?,
    onPickerField: (String?) -> Unit
) {
    var editing by remember { mutableStateOf(EditPalette.DARK) }
    val systemDark = isSystemInDarkTheme()
    val activeIsDark = when (controller.working.mode) {
        RoomThemeMode.LIGHT -> false
        RoomThemeMode.DARK, RoomThemeMode.AMOLED -> true
        RoomThemeMode.AUTO -> systemDark
    }
    LaunchedEffect(controller.working.mode, systemDark) {
        editing = if (activeIsDark) EditPalette.DARK else EditPalette.LIGHT
    }

    SectionHeader("Colors")
    Row(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = editing == EditPalette.LIGHT,
            onClick = { editing = EditPalette.LIGHT },
            label = { Text("Light palette") }
        )
        FilterChip(
            selected = editing == EditPalette.DARK,
            onClick = { editing = EditPalette.DARK },
            label = { Text("Dark palette") }
        )
    }

    val colors = if (editing == EditPalette.LIGHT) controller.working.light else controller.working.dark
    val colorRows = listOf(
        "Background" to colors.background,
        "Surface / cards" to colors.surface,
        "Surface (alternate)" to colors.surfaceAlt,
        "Primary accent" to colors.primary,
        "Secondary accent" to colors.secondary,
        "Text" to colors.textPrimary,
        "Secondary text" to colors.textSecondary,
        "Address bar" to colors.addressBar,
        "Tab bar" to colors.tabBar,
        "Navigation bar" to colors.navBar,
        "Button" to colors.button,
        "Border" to colors.border,
        "Icon" to colors.icon,
        "Selection / highlight" to colors.selection
    )

    colorRows.forEach { (label, color) ->
        val setter: (Long) -> Unit = { newColor ->
            controller.update { spec ->
                val base = if (editing == EditPalette.LIGHT) spec.light else spec.dark
                val patched = when (label) {
                    "Background" -> base.copy(background = newColor)
                    "Surface / cards" -> base.copy(surface = newColor)
                    "Surface (alternate)" -> base.copy(surfaceAlt = newColor)
                    "Primary accent" -> base.copy(primary = newColor)
                    "Secondary accent" -> base.copy(secondary = newColor)
                    "Text" -> base.copy(textPrimary = newColor)
                    "Secondary text" -> base.copy(textSecondary = newColor)
                    "Address bar" -> base.copy(addressBar = newColor)
                    "Tab bar" -> base.copy(tabBar = newColor)
                    "Navigation bar" -> base.copy(navBar = newColor)
                    "Button" -> base.copy(button = newColor)
                    "Border" -> base.copy(border = newColor)
                    "Icon" -> base.copy(icon = newColor)
                    else -> base.copy(selection = newColor)
                }
                if (editing == EditPalette.LIGHT) spec.copy(light = patched) else spec.copy(dark = patched)
            }
        }
        ColorRow(label = label, color = color) { onPickerField(label) }
        if (pickerField == label) {
            ColorPickerDialog(
                title = label,
                initial = color,
                onDismiss = { onPickerField(null) },
                onPick = { setter(it); onPickerField(null) }
            )
        }
    }
}

private enum class EditPalette { LIGHT, DARK }

@Composable
private fun ColorRow(label: String, color: Long, onClick: () -> Unit) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(color))
                .border(0.5.dp, extras.border, RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = extras.textPrimary, modifier = Modifier.weight(1f))
        Text(
            "#" + java.lang.Long.toHexString(color and 0xFFFFFFL).uppercase().padStart(6, '0'),
            style = MaterialTheme.typography.labelMedium,
            color = extras.textSecondary
        )
    }
}

// ---------------------------------------------------------------------------
// Color picker dialog — curated professional swatches + hex input
// ---------------------------------------------------------------------------

private val PICKER_SWATCHES = listOf(
    0xFF000000L, 0xFF111114L, 0xFF1B1B24L, 0xFF2A2A38L, 0xFF3A3A4AL, 0xFF54546AL,
    0xFF76768CL, 0xFF9A9AB0L, 0xFFC3C3D4L, 0xFFE7E7F0L, 0xFFFFFFFFL, 0xFFF7F6FBL,
    0xFF0B0B0FL, 0xFF0E1622L, 0xFF0A1929L, 0xFF07130DL, 0xFF1A0E0AL, 0xFF170F13L,
    0xFF150C10L, 0xFF14121CL, 0xFF05070DL, 0xFF0F1114L, 0xFF061418L, 0xFF15100BL,
    0xFF6D28D9L, 0xFF7C3AEDL, 0xFF8B5CF6L, 0xFFA78BFAL, 0xFFC084FCL, 0xFFC4B5FDL,
    0xFF4338CAL, 0xFF4F46E5L, 0xFF6366F1L, 0xFF818CF8L, 0xFF1E3A8AL, 0xFF3B82F6L,
    0xFF60A5FAL, 0xFF93C5FDL, 0xFF0277BDL, 0xFF0284C7L, 0xFF38BDF8L, 0xFF7DD3FCL,
    0xFF047857L, 0xFF10B981L, 0xFF34D399L, 0xFFA7F3D0L, 0xFF3A5A40L, 0xFF588157L,
    0xFF7FB069L, 0xFFA3B18AL, 0xFF0D9488L, 0xFF06B6D4L, 0xFF2DD4BFL, 0xFF22D3EEL,
    0xFFB91C1CL, 0xFFDC2626L, 0xFFEF4444L, 0xFFF87171L, 0xFFDB2777L, 0xFFE11D48L,
    0xFFF472B6L, 0xFFF9A8D4L, 0xFFEA580CL, 0xFFFB923CL, 0xFFFBBF24L, 0xFFF5C542L,
    0xFFD4AF37L, 0xFFB8860BL, 0xFF8B5E34L, 0xFFA47148L, 0xFFB08968L, 0xFFD7A86EL,
    0xFF0891B2L, 0xFF00E5FFL, 0xFFC026D3L, 0xFF191822L, 0xFF5A5870L, 0xFF12101AL
)

@Composable
private fun ColorPickerDialog(
    title: String,
    initial: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    var hex by remember {
        mutableStateOf(
            java.lang.Long.toHexString(initial and 0xFFFFFFL).uppercase().padStart(6, '0')
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PICKER_SWATCHES.forEach { swatch ->
                        val selected = swatch == initial
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(swatch))
                                .border(
                                    width = if (selected) 2.5.dp else 0.5.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onPick(swatch) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = hex,
                    onValueChange = { input ->
                        hex = input.filter { it.isLetterOrDigit() }.take(6).uppercase()
                    },
                    label = { Text("Hex color (RRGGBB)") },
                    prefix = { Text("#") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cleaned = hex.removePrefix("#")
                    if (cleaned.length == 6) {
                        runCatching { ("FF" + cleaned).toLong(16) }.getOrNull()?.let(onPick)
                    }
                },
                enabled = hex.length == 6
            ) { Text("Use color") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------------------
// Slider row + small dialogs
// ---------------------------------------------------------------------------

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    suffix: String,
    onValueChange: (Float) -> Unit
) {
    val extras = LocalRoomExtras.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = extras.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text("${value.toInt()}$suffix", style = MaterialTheme.typography.labelMedium, color = extras.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}

private fun directionLabel(dir: GradientDirection): String = when (dir) {
    GradientDirection.TOP_BOTTOM -> "Top → Bottom"
    GradientDirection.BOTTOM_TOP -> "Bottom → Top"
    GradientDirection.LEFT_RIGHT -> "Left → Right"
    GradientDirection.RIGHT_LEFT -> "Right → Left"
    GradientDirection.TL_BR -> "Diagonal ↘"
    GradientDirection.TR_BL -> "Diagonal ↙"
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ImportThemeDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var raw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import theme") },
        text = {
            Column {
                Text(
                    "Paste a Room Browser theme JSON (exported from this studio).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(raw) }, enabled = raw.isNotBlank()) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
