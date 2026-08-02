package com.opentasker.core.contexts

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.TileService
import com.opentasker.app.R

private const val MAX_LABEL_CHARS = 40
private const val MAX_SUBTITLE_CHARS = 80

data class QuickSettingsTileConfig(
    val slot: Int,
    val taskId: Long? = null,
    val taskName: String = "",
    val label: String = "",
    val subtitle: String = "",
    val iconKey: String = QuickSettingsTileIcons.DEFAULT,
    val active: Boolean = false,
)

object QuickSettingsTileSlots {
    const val COUNT = 4
    const val DEFAULT = 1

    fun normalize(slot: Int): Int? = slot.takeIf { it in 1..COUNT }

    fun componentClass(slot: Int): Class<out QuickSettingsTileService>? = when (slot) {
        1 -> QuickSettingsTileService::class.java
        2 -> QuickSettingsTileServiceSlot2::class.java
        3 -> QuickSettingsTileServiceSlot3::class.java
        4 -> QuickSettingsTileServiceSlot4::class.java
        else -> null
    }

    fun slotForComponent(className: String?): Int? = when (className) {
        QuickSettingsTileService::class.java.name -> 1
        QuickSettingsTileServiceSlot2::class.java.name -> 2
        QuickSettingsTileServiceSlot3::class.java.name -> 3
        QuickSettingsTileServiceSlot4::class.java.name -> 4
        else -> null
    }
}

object QuickSettingsTileIcons {
    const val DEFAULT = "play"
    const val STAR = "star"
    const val SETTINGS = "settings"
    const val BOLT = "bolt"

    val keys = listOf(DEFAULT, STAR, SETTINGS, BOLT)

    fun resourceId(key: String): Int = when (key) {
        STAR -> R.drawable.ic_qs_tile_star
        SETTINGS -> R.drawable.ic_qs_tile_settings
        BOLT -> R.drawable.ic_qs_tile_bolt
        else -> R.drawable.ic_qs_tile_play
    }
}

class QuickSettingsTileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(slot: Int): QuickSettingsTileConfig {
        val normalized = requireNotNull(QuickSettingsTileSlots.normalize(slot)) { "Invalid tile slot: $slot" }
        val prefix = prefix(normalized)
        val taskId = preferences.getLong(prefix + KEY_TASK_ID, NO_TASK)
        return QuickSettingsTileConfig(
            slot = normalized,
            taskId = taskId.takeIf { it > 0L },
            taskName = preferences.getString(prefix + KEY_TASK_NAME, "").orEmpty(),
            label = preferences.getString(prefix + KEY_LABEL, "").orEmpty(),
            subtitle = preferences.getString(prefix + KEY_SUBTITLE, "").orEmpty(),
            iconKey = preferences.getString(prefix + KEY_ICON, QuickSettingsTileIcons.DEFAULT)
                ?.takeIf { it in QuickSettingsTileIcons.keys }
                ?: QuickSettingsTileIcons.DEFAULT,
            active = preferences.getBoolean(prefix + KEY_ACTIVE, false),
        )
    }

    fun save(config: QuickSettingsTileConfig) {
        val normalized = requireNotNull(QuickSettingsTileSlots.normalize(config.slot)) { "Invalid tile slot: ${config.slot}" }
        val prefix = prefix(normalized)
        preferences.edit()
            .putLong(prefix + KEY_TASK_ID, config.taskId ?: NO_TASK)
            .putString(prefix + KEY_TASK_NAME, clean(config.taskName, MAX_LABEL_CHARS))
            .putString(prefix + KEY_LABEL, clean(config.label, MAX_LABEL_CHARS))
            .putString(prefix + KEY_SUBTITLE, clean(config.subtitle, MAX_SUBTITLE_CHARS))
            .putString(
                prefix + KEY_ICON,
                config.iconKey.takeIf { it in QuickSettingsTileIcons.keys } ?: QuickSettingsTileIcons.DEFAULT,
            )
            .putBoolean(prefix + KEY_ACTIVE, config.active)
            .apply()
    }

    fun setState(slot: Int, active: Boolean, label: String? = null, subtitle: String? = null, iconKey: String? = null): QuickSettingsTileConfig {
        val current = load(slot)
        val updated = updateQuickSettingsTileConfig(
            current = current,
            active = active,
            label = label,
            subtitle = subtitle,
            iconKey = iconKey,
        )
        save(updated)
        return updated
    }

    companion object {
        const val PREFERENCES = "quick_settings_tiles"
        private const val NO_TASK = -1L
        private const val KEY_TASK_ID = "task_id"
        private const val KEY_TASK_NAME = "task_name"
        private const val KEY_LABEL = "label"
        private const val KEY_SUBTITLE = "subtitle"
        private const val KEY_ICON = "icon"
        private const val KEY_ACTIVE = "active"

        internal fun clean(value: String, maxChars: Int): String = value
            .filterNot(Char::isISOControl)
            .trim()
            .take(maxChars)

        private fun prefix(slot: Int): String = "slot_${slot}_"
    }
}

internal fun updateQuickSettingsTileConfig(
    current: QuickSettingsTileConfig,
    active: Boolean,
    label: String? = null,
    subtitle: String? = null,
    iconKey: String? = null,
): QuickSettingsTileConfig = current.copy(
    active = active,
    label = label?.let { QuickSettingsTileStore.clean(it, MAX_LABEL_CHARS) } ?: current.label,
    subtitle = subtitle?.let { QuickSettingsTileStore.clean(it, MAX_SUBTITLE_CHARS) } ?: current.subtitle,
    iconKey = iconKey?.takeIf { it in QuickSettingsTileIcons.keys } ?: current.iconKey,
)

fun QuickSettingsTileStore.requestRefresh(context: Context, slot: Int) {
    QuickSettingsTileSlots.componentClass(slot)?.let { serviceClass ->
        runCatching {
            TileService.requestListeningState(context.applicationContext, ComponentName(context, serviceClass))
        }
    }
}

fun QuickSettingsTileConfig.icon(context: Context): Icon = Icon.createWithResource(
    context,
    QuickSettingsTileIcons.resourceId(iconKey),
)
