package com.opentasker.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PickableApp(val label: String, val pkg: String)

/** Persisted tile knobs for the app picker (icon dp, label/id sp, label bold, grid padding dp). */
private object AppPickerPrefs {
    private const val PREFS = "app_picker_prefs"
    private const val KEY_ICON = "icon_dp"
    private const val KEY_LABEL = "label_sp"
    private const val KEY_PKG = "pkg_sp"
    private const val KEY_BOLD = "label_bold"
    private const val KEY_PAD_V = "pad_v_dp"
    private const val KEY_PAD_H = "pad_h_dp"
    const val DEFAULT_ICON_DP = 72   // 48dp * 1.5 (白い熊, 2026-07-16)
    const val DEFAULT_LABEL_SP = 14  // raised + bold (白い熊, 2026-07-16)
    const val DEFAULT_PKG_SP = 9
    const val DEFAULT_BOLD = true
    const val DEFAULT_PAD_V_DP = 4   // row gap — was a fixed 12dp, too airy
    const val DEFAULT_PAD_H_DP = 8   // column gap

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun iconDp(context: Context) = prefs(context).getInt(KEY_ICON, DEFAULT_ICON_DP)
    fun labelSp(context: Context) = prefs(context).getInt(KEY_LABEL, DEFAULT_LABEL_SP)
    fun pkgSp(context: Context) = prefs(context).getInt(KEY_PKG, DEFAULT_PKG_SP)
    fun labelBold(context: Context) = prefs(context).getBoolean(KEY_BOLD, DEFAULT_BOLD)
    fun padVDp(context: Context) = prefs(context).getInt(KEY_PAD_V, DEFAULT_PAD_V_DP)
    fun padHDp(context: Context) = prefs(context).getInt(KEY_PAD_H, DEFAULT_PAD_H_DP)
    fun save(context: Context, iconDp: Int, labelSp: Int, pkgSp: Int, bold: Boolean, padV: Int, padH: Int) {
        prefs(context).edit()
            .putInt(KEY_ICON, iconDp).putInt(KEY_LABEL, labelSp).putInt(KEY_PKG, pkgSp)
            .putBoolean(KEY_BOLD, bold).putInt(KEY_PAD_V, padV).putInt(KEY_PAD_H, padH)
            .apply()
    }
}

/**
 * A searchable, multi-select picker over the installed **user** apps — including frozen/disabled ones,
 * so the generated unfreeze-then-launch tasks can target apps that are currently frozen. Apps are shown
 * as a near-fullscreen grid of icon tiles (icon + name + package id); the search matches BOTH the label
 * and the package id, and the id line under each label shows *why* a tile matched. A ⚙ panel exposes
 * the tile sizing (icon dp, label sp, id sp), persisted across invocations. The OK button hands back
 * the selected (package, label) pairs.
 *
 * [restrictPackages] non-null limits the grid to exactly those packages (own package included —
 * needed when the caller's list contains this app itself). [singleSelect] turns the grid into a
 * one-tap chooser: tapping a tile confirms that single app immediately (no OK button).
 * [includeSelf] keeps THIS app in an unrestricted grid — normally hidden (a task can't sensibly
 * blacklist its own app), but a backup-target list must be able to include 自由作業盤 itself.
 * An already-selected own package is always shown, so a stored selection is never invisible.
 */
@Composable
internal fun AppMultiSelectDialog(
    title: String,
    preselected: Set<String> = emptySet(),
    restrictPackages: Set<String>? = null,
    singleSelect: Boolean = false,
    includeSelf: Boolean = false,
    onConfirm: (List<Pair<String, String>>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<PickableApp>?>(null) }
    // package -> label of the currently-checked apps.
    val selected = remember { mutableStateMapOf<String, String>() }
    // Tile sizing knobs (persisted on change).
    var iconDp by remember { mutableIntStateOf(AppPickerPrefs.iconDp(context)) }
    var labelSp by remember { mutableIntStateOf(AppPickerPrefs.labelSp(context)) }
    var pkgSp by remember { mutableIntStateOf(AppPickerPrefs.pkgSp(context)) }
    var labelBold by remember { mutableStateOf(AppPickerPrefs.labelBold(context)) }
    var padV by remember { mutableIntStateOf(AppPickerPrefs.padVDp(context)) }
    var padH by remember { mutableIntStateOf(AppPickerPrefs.padHDp(context)) }
    var showSizing by remember { mutableStateOf(false) }
    fun persist() = AppPickerPrefs.save(context, iconDp, labelSp, pkgSp, labelBold, padV, padH)

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val ownPkg = context.packageName
            runCatching {
                val all = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getInstalledApplications(
                        PackageManager.ApplicationInfoFlags.of(
                            (PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong()
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(
                        PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
                    )
                }
                all.asSequence()
                    .filter { info ->
                        if (restrictPackages != null) {
                            // Restricted mode shows exactly the caller's list — own package included.
                            info.packageName in restrictPackages
                        } else {
                            // Keep USER apps: non-system, or a user-updated system app.
                            ((info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) &&
                                (info.packageName != ownPkg || includeSelf || info.packageName in preselected)
                        }
                    }
                    .map { PickableApp(pm.getApplicationLabel(it).toString(), it.packageName) }
                    .distinctBy { it.pkg }
                    .toList()
            }.getOrDefault(emptyList())
        }
        // Pre-tick the packages already in the list, and show them FIRST (each group alphabetical), so the
        // current selection is visible up top and easy to amend. Ordering keys off the INITIAL set so
        // toggling a tile doesn't make the grid jump around.
        val byPkg = loaded.associateBy { it.pkg }
        preselected.forEach { pkg -> selected[pkg] = byPkg[pkg]?.label ?: pkg }
        apps = loaded.sortedWith(
            compareByDescending<PickableApp> { it.pkg in preselected }.thenBy { it.label.lowercase() },
        )
    }

    // Near-fullscreen dialog: still a Dialog window (scrim, outside-cancel), not a page —
    // usePlatformDefaultWidth=false lifts the platform's ~560dp cap so it can span the screen.
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.94f)
                .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title.ifBlank { "Select apps" },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showSizing = !showSizing }) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = "Tile sizing",
                            tint = if (showSizing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showSizing) {
                    // Inline sizing panel: −/+ steppers + bold toggle, persisted immediately.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SizeStepper("Icon", iconDp, 32, 160, 8) { iconDp = it; persist() }
                        SizeStepper("Label", labelSp, 7, 28, 1) { labelSp = it; persist() }
                        SizeStepper("ID", pkgSp, 6, 20, 1) { pkgSp = it; persist() }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SizeStepper("Pad↕", padV, 0, 32, 2) { padV = it; persist() }
                        SizeStepper("Pad↔", padH, 0, 32, 2) { padH = it; persist() }
                        TextButton(onClick = { labelBold = !labelBold; persist() }) {
                            Text(if (labelBold) "Bold ✓" else "Bold", fontWeight = if (labelBold) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search (name or app id)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val filtered = list.filter {
                        query.isBlank() ||
                            it.label.contains(query, ignoreCase = true) ||
                            it.pkg.contains(query, ignoreCase = true)
                    }
                    LazyVerticalGrid(
                        // Cell width tracks the icon size so bigger icons get room instead of clipping.
                        columns = GridCells.Adaptive(minSize = maxOf(84, iconDp + 32).dp),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(padH.dp),
                        verticalArrangement = Arrangement.spacedBy(padV.dp),
                    ) {
                        items(filtered, key = { it.pkg }) { app ->
                            SelectableAppTile(
                                pkg = app.pkg,
                                label = app.label,
                                iconDp = iconDp,
                                labelSp = labelSp,
                                pkgSp = pkgSp,
                                labelBold = labelBold,
                                padV = padV,
                                padH = padH,
                                selected = selected.containsKey(app.pkg),
                                onToggle = {
                                    if (singleSelect) {
                                        onConfirm(listOf(app.pkg to app.label))
                                    } else if (selected.containsKey(app.pkg)) {
                                        selected.remove(app.pkg)
                                    } else {
                                        selected[app.pkg] = app.label
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    if (!singleSelect) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            onConfirm(selected.map { (pkg, label) -> pkg to label })
                        }) { Text("OK (${selected.size})") }
                    }
                }
            }
        }
    }
}

/** A compact −/value/+ stepper for the sizing panel. */
@Composable
private fun SizeStepper(label: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−") }
        Text("$value", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Text("+") }
    }
}

@Composable
private fun SelectableAppTile(
    pkg: String,
    label: String,
    iconDp: Int,
    labelSp: Int,
    pkgSp: Int,
    labelBold: Boolean,
    padV: Int,
    padH: Int,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val icon by produceState<ImageBitmap?>(initialValue = null, pkg, iconDp) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // Rasterize at the real pixel size so a large icon stays sharp.
                val px = with(density) { iconDp.dp.roundToPx() }.coerceAtLeast(48)
                context.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
            }.getOrNull()
        }
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)) else Modifier)
            .clickable(onClick = onToggle)
            // Tile-inner padding follows the panel knobs at half strength (the grid gap is the other half).
            .padding(vertical = (padV / 2).dp, horizontal = (padH / 2).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(iconDp.dp), contentAlignment = Alignment.Center) {
            val bmp = icon
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(iconDp.dp))
            } else {
                Box(
                    Modifier
                        .size((iconDp * 5 / 6).dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                )
            }
            if (selected) {
                // A check badge on a solid backdrop so it reads over any icon.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = labelSp.sp,
            lineHeight = (labelSp + 2).sp,
            fontWeight = if (labelBold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        // The app id under the label — also what the search matches, so a package-only hit is explicable.
        Text(
            pkg,
            fontSize = pkgSp.sp,
            lineHeight = (pkgSp + 2).sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
