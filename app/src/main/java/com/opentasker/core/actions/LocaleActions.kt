package com.opentasker.core.actions

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// System-locale actions (白い熊: locale-switch task, 2026-07-23). The 2026-07-22 ja switch wiped
// contacts2.db (EMUI's contacts provider corrupts mid-rebuild on locale change), so locale flips
// are driven by a task that backs contacts up first — these actions are its read/write halves.
// ---------------------------------------------------------------------------------------------

private fun currentSystemLocale(): Locale = Resources.getSystem().configuration.locales[0]

/**
 * `Get Locale` — store the current system locale into variables.
 *
 * Args:
 *   - "var":          variable for the full BCP-47 tag, e.g. "ja-CZ" (required).
 *   - "language_var": optional variable for just the language code, e.g. "ja" —
 *                     the toggle/already-set checks compare this, ignoring region.
 */
class GetLocaleAction : Action {
    override val id = "system.get_locale"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val varName = args["var"]?.trim()?.ifBlank { null }
            ?: return ActionResult.Failure("missing var")
        val locale = currentSystemLocale()
        ctx.variables.set(varName, locale.toLanguageTag())
        args["language_var"]?.trim()?.ifBlank { null }?.let { ctx.variables.set(it, locale.language) }
        ctx.logger("Get locale: ${locale.toLanguageTag()} → %$varName")
        return ActionResult.Success
    }
}

/**
 * `Set Locale` — change the system locale, persistently, without root.
 *
 * Args:
 *   - "locale":     a BCP-47 tag ("en-CZ"), or two comma-separated tags ("ja-CZ,en-CZ")
 *                   meaning toggle: set the first whose language differs from the current one.
 *   - "result_var": optional variable that receives the tag actually set.
 *
 * Mechanism (EMUI 12 / Android 12 — no LocaleManager): requires CHANGE_CONFIGURATION, whose
 * "development" protection flag makes it adb-grantable once, surviving reboots:
 *   adb shell pm grant shiroikuma.jiyusagyoban android.permission.CHANGE_CONFIGURATION
 * Then IActivityManager.updatePersistentConfiguration (greylisted, reflection) with a
 * Configuration carrying the new LocaleList and userSetLocale=true — the classic MoreLocale
 * path; persists via persist.sys.locale / Settings.System.
 */
class SetLocaleAction : Action {
    override val id = "system.set_locale"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val spec = args["locale"]?.trim()?.ifBlank { null }
            ?: return ActionResult.Failure("missing locale (a BCP-47 tag, or \"tagA,tagB\" to toggle)")
        val candidates = spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return ActionResult.Failure("missing locale")
        val parsed = candidates.map { tag ->
            val loc = Locale.forLanguageTag(tag)
            if (loc.language.isEmpty()) return ActionResult.Failure("invalid locale tag: $tag")
            loc
        }

        val current = currentSystemLocale()
        // Single tag → set it. A pair → toggle: the first entry whose language isn't current.
        val target = parsed.firstOrNull { it.language != current.language }
        if (target == null) {
            // e.g. toggle pair "ja,ja" — nothing to change to.
            return ActionResult.Failure("no candidate in \"$spec\" differs from the current locale (${current.toLanguageTag()})")
        }

        if (ctx.app.checkSelfPermission(android.Manifest.permission.CHANGE_CONFIGURATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.Failure(
                "CHANGE_CONFIGURATION not granted — run once over adb: " +
                    "pm grant ${ctx.app.packageName} android.permission.CHANGE_CONFIGURATION",
            )
        }
        // updatePersistentConfiguration additionally enforces the WRITE_SETTINGS appop
        // (seen live on EMUI 12, run log 2026-07-23).
        if (!android.provider.Settings.System.canWrite(ctx.app)) {
            return ActionResult.Failure(
                "WRITE_SETTINGS not granted — run once over adb: " +
                    "appops set ${ctx.app.packageName} WRITE_SETTINGS allow " +
                    "(or enable \"modify system settings\" for the app)",
            )
        }

        return try {
            // IActivityManager (hidden): getConfiguration → set locales → updatePersistentConfiguration.
            val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
                ?: return ActionResult.Failure("ActivityManager.getService() returned null")
            val config = am.javaClass.getMethod("getConfiguration").invoke(am) as Configuration
            // Reorder the EXISTING locale list so the target is primary; keep every other installed
            // language. Replacing the list with LocaleList(target) alone dropped the other language
            // from the system (EMUI removed English from 言語と地域 on the ja switch, 白い熊 2026-07-23).
            val existing = config.locales
            val ordered = buildList {
                add(target)
                for (i in 0 until existing.size()) {
                    val loc = existing[i]
                    if (loc.toLanguageTag() != target.toLanguageTag()) add(loc)
                }
            }
            config.setLocales(LocaleList(*ordered.toTypedArray()))
            // userSetLocale=true marks the change as user-chosen so the framework persists it.
            runCatching { Configuration::class.java.getField("userSetLocale").setBoolean(config, true) }
            am.javaClass.getMethod("updatePersistentConfiguration", Configuration::class.java).invoke(am, config)
            args["result_var"]?.trim()?.ifBlank { null }?.let { ctx.variables.set(it, target.toLanguageTag()) }
            ctx.logger("Set locale: ${current.toLanguageTag()} → ${target.toLanguageTag()}")
            ActionResult.Success
        } catch (e: Exception) {
            // A hidden-API block surfaces as NoSuchMethodException — name it so the fallback
            // (HiddenApiBypass / Shizuku user-service) can be decided from the run log.
            ActionResult.Failure("set locale failed: ${e.cause?.message ?: e.message ?: e.javaClass.simpleName}")
        }
    }
}
