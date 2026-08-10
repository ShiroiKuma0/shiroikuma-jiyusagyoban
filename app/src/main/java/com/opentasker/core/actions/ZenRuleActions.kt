package com.opentasker.core.actions

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenDeviceEffects
import androidx.annotation.RequiresApi
import com.opentasker.app.MainActivity
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

private const val AUTOMATIC_ZEN_RULE_API = 35
private const val ZEN_RULE_PREFS = "zen_rule_bindings"
private const val ZEN_RULE_KEY_PATTERN = "^[A-Za-z0-9._-]{1,64}$"

internal data class ZenRuleSpec(
    val id: String,
    val name: String,
    val mode: String,
    val interruptionFilter: Int,
    val enabled: Boolean,
    val grayscale: Boolean,
    val dimWallpaper: Boolean,
    val nightMode: Boolean,
)

internal object ZenRuleActionSupport {
    fun usesAutomaticRules(sdkInt: Int): Boolean = sdkInt >= AUTOMATIC_ZEN_RULE_API

    fun interruptionFilterFor(mode: String): Int? = when (mode.trim().lowercase()) {
        "off", "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
        "priority", "priority_only" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        "alarms", "alarms_only" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
        "total_silence", "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
        else -> null
    }

    fun parse(args: Map<String, String>): Result<ZenRuleSpec> {
        val id = args["id"]?.trim().orEmpty()
        if (!Regex(ZEN_RULE_KEY_PATTERN).matches(id)) {
            return Result.failure(IllegalArgumentException("invalid rule id: use 1-64 letters, numbers, dot, dash, or underscore"))
        }
        val name = args["name"]?.trim().orEmpty()
        if (name.isEmpty() || name.length > 80) {
            return Result.failure(IllegalArgumentException("rule name must contain 1-80 characters"))
        }
        val mode = args["mode"]?.trim()?.lowercase().orEmpty().ifEmpty { "total_silence" }
        val filter = interruptionFilterFor(mode)
            ?: return Result.failure(IllegalArgumentException("invalid Zen mode: $mode (use off/priority/alarms/total_silence)"))
        val enabled = args.booleanValue("enabled", true)
            ?: return Result.failure(IllegalArgumentException("enabled must be true or false"))
        val grayscale = args.booleanValue("grayscale", false)
            ?: return Result.failure(IllegalArgumentException("grayscale must be true or false"))
        val dimWallpaper = args.booleanValue("dim_wallpaper", false)
            ?: return Result.failure(IllegalArgumentException("dim_wallpaper must be true or false"))
        val nightMode = args.booleanValue("night_mode", false)
            ?: return Result.failure(IllegalArgumentException("night_mode must be true or false"))
        return Result.success(
            ZenRuleSpec(id, name, mode, filter, enabled, grayscale, dimWallpaper, nightMode),
        )
    }

    fun conditionId(context: Context, id: String): Uri = Uri.Builder()
        .scheme(Condition.SCHEME)
        .authority(context.packageName)
        .appendPath("opentasker")
        .appendPath(id)
        .build()

    private fun Map<String, String>.booleanValue(key: String, default: Boolean): Boolean? =
        this[key]?.toBooleanStrictOrNull() ?: if (key in this) null else default
}

private class ZenRuleStore(context: Context) {
    private val prefs = context.getSharedPreferences(ZEN_RULE_PREFS, Context.MODE_PRIVATE)

    fun get(id: String): String? = prefs.getString(id, null)

    fun put(id: String, systemId: String) {
        prefs.edit().putString(id, systemId).apply()
    }

    fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }
}

class ZenRuleSetAction(
    private val sdkInt: () -> Int = { Build.VERSION.SDK_INT },
) : DeclaredAction(ActionCatalog.require("zen.rule.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val spec = ZenRuleActionSupport.parse(args).getOrElse {
            return ActionResult.Failure(it.message ?: "invalid Zen rule arguments")
        }
        val nm = ctx.app.getSystemService(NotificationManager::class.java)
            ?: return ActionResult.Failure("notification service not available")
        if (!nm.isNotificationPolicyAccessGranted) {
            return ActionResult.Failure("Do Not Disturb access is not granted; enable it in Setup")
        }
        if (Build.VERSION.SDK_INT < AUTOMATIC_ZEN_RULE_API || !ZenRuleActionSupport.usesAutomaticRules(sdkInt())) {
            return try {
                nm.setInterruptionFilter(spec.interruptionFilter)
                ctx.logger("Zen rule ${spec.id}: ${spec.mode} (transient DND fallback)")
                ActionResult.Success
            } catch (ex: SecurityException) {
                ActionResult.Failure("DND change blocked: ${ex.message}", ex)
            }
        }
        return applyAutomaticRule(ctx, nm, spec)
    }

    @RequiresApi(AUTOMATIC_ZEN_RULE_API)
    private fun applyAutomaticRule(
        ctx: ActionContext,
        nm: NotificationManager,
        spec: ZenRuleSpec,
    ): ActionResult {
        return try {
            val store = ZenRuleStore(ctx.app)
            val conditionId = ZenRuleActionSupport.conditionId(ctx.app, spec.id)
            val existingId = store.get(spec.id)
            val existingRule = existingId?.let { nm.getAutomaticZenRule(it) }
            if (existingId != null && existingRule == null) {
                store.remove(spec.id)
            }
            val rule = AutomaticZenRule.Builder(spec.name, conditionId)
                .setConfigurationActivity(ComponentName(ctx.app, MainActivity::class.java))
                .setInterruptionFilter(spec.interruptionFilter)
                .setEnabled(spec.enabled)
                .setDeviceEffects(
                    ZenDeviceEffects.Builder()
                        .setShouldDisplayGrayscale(spec.grayscale)
                        .setShouldDimWallpaper(spec.dimWallpaper)
                        .setShouldUseNightMode(spec.nightMode)
                        .build(),
                )
                .build()
            val systemId = if (existingId != null && existingRule != null) {
                if (!nm.updateAutomaticZenRule(existingId, rule)) {
                    return ActionResult.Failure("system rejected the Zen rule update")
                }
                existingId
            } else {
                nm.addAutomaticZenRule(rule)
                    ?: return ActionResult.Failure("system rejected the Zen rule")
            }
            store.put(spec.id, systemId)
            nm.setAutomaticZenRuleState(
                systemId,
                Condition(conditionId, spec.name, Condition.STATE_TRUE),
            )
            ctx.logger("Zen rule ${spec.id}: ${spec.mode}, effects=" + effectsSummary(spec))
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("Zen rule change blocked: ${ex.message}", ex)
        } catch (ex: IllegalArgumentException) {
            ActionResult.Failure("Zen rule rejected: ${ex.message}", ex)
        }
    }

    private fun effectsSummary(spec: ZenRuleSpec): String = buildList {
        if (spec.grayscale) add("grayscale")
        if (spec.dimWallpaper) add("dim_wallpaper")
        if (spec.nightMode) add("night_mode")
    }.ifEmpty { listOf("none") }.joinToString(",")
}

class ZenRuleClearAction(
    private val sdkInt: () -> Int = { Build.VERSION.SDK_INT },
) : DeclaredAction(ActionCatalog.require("zen.rule.clear")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val id = args["id"]?.trim().orEmpty()
        if (!Regex(ZEN_RULE_KEY_PATTERN).matches(id)) {
            return ActionResult.Failure("invalid rule id: use 1-64 letters, numbers, dot, dash, or underscore")
        }
        val nm = ctx.app.getSystemService(NotificationManager::class.java)
            ?: return ActionResult.Failure("notification service not available")
        if (!nm.isNotificationPolicyAccessGranted) {
            return ActionResult.Failure("Do Not Disturb access is not granted; enable it in Setup")
        }
        if (Build.VERSION.SDK_INT < AUTOMATIC_ZEN_RULE_API || !ZenRuleActionSupport.usesAutomaticRules(sdkInt())) {
            return try {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                ctx.logger("Zen rule $id: cleared (transient DND fallback)")
                ActionResult.Success
            } catch (ex: SecurityException) {
                ActionResult.Failure("DND change blocked: ${ex.message}", ex)
            }
        }
        return clearAutomaticRule(ctx, nm, id)
    }

    @RequiresApi(AUTOMATIC_ZEN_RULE_API)
    private fun clearAutomaticRule(ctx: ActionContext, nm: NotificationManager, id: String): ActionResult {
        val store = ZenRuleStore(ctx.app)
        val systemId = store.get(id) ?: return ActionResult.Success
        return try {
            val rule = nm.getAutomaticZenRule(systemId)
            if (rule == null) {
                store.remove(id)
                return ActionResult.Success
            }
            nm.setAutomaticZenRuleState(
                systemId,
                Condition(rule.conditionId, rule.name, Condition.STATE_FALSE),
            )
            if (!nm.removeAutomaticZenRule(systemId)) {
                return ActionResult.Failure("system rejected the Zen rule removal")
            }
            store.remove(id)
            ctx.logger("Zen rule $id: cleared")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("Zen rule removal blocked: ${ex.message}", ex)
        } catch (ex: IllegalArgumentException) {
            ActionResult.Failure("Zen rule removal rejected: ${ex.message}", ex)
        }
    }
}
