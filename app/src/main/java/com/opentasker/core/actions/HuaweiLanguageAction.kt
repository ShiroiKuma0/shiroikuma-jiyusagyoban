package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner

/**
 * `Huawei Band language` — set the language shown ON THE BAND, and its unit system.
 *
 * ## Why this action has to exist
 *
 * The band has no language item in its own Settings, and that is by design rather than an
 * oversight: the COMPANION owns the setting and pushes it down. Huawei documents the behaviour
 * plainly — *"the language cannot be set on your wearable device directly … the time and language
 * settings on the phone will automatically sync to the wearable device."* The picker shown after a
 * factory reset is real, but the first companion to connect overwrites whatever was chosen there.
 *
 * So without this command the only ways to change the language are a factory reset (which wipes the
 * band's unsynced history) or handing the band to Huawei Health (an account, and a re-pair). One
 * frame on a session we already open replaces both.
 *
 * This is also the direct cause of the state 白い熊's band was found in: it went to another phone
 * for a diagnostic capture, that phone's Health pushed `en-US`, and nothing here had ever sent the
 * command — so the band kept the last word it was given.
 *
 * ## Arguments
 *
 *  * `locale` — a BCP-47 tag in `xx-YY` form, e.g. `ja-JP`. Defaults to the last one accepted.
 *  * `units` — `metric` (default) or `imperial`. The same frame carries both; there is no way to
 *    set one without stating the other, so a caller that omits it gets metric rather than a guess.
 *  * `address`, `prefix`, `store` — as the other Huawei actions.
 *
 * An unsupported locale is not an error: outside mainland China the band falls back to English. So
 * a wrong tag costs a language, not a band — which is why this sends and reports rather than
 * validating a list of tags we would have to keep in step with Huawei's firmware.
 */
class HuaweiLanguageAction : Action {
    override val id = "huawei.language"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }
        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)
        val locale = args["locale"]?.trim()?.ifEmpty { null }
            ?: HuaweiSettings.bandLocale(ctx.app)
            ?: return fail(ctx, prefix, store, "no locale given — pass e.g. locale=ja-JP")
        val imperial = args["units"]?.trim()?.lowercase() == "imperial"

        val result = HuaweiSyncRunner.setBandLocale(ctx.app, address, locale, imperial)
        return result.fold(
            onSuccess = { accepted ->
                val units = if (imperial) "imperial" else "metric"
                if (accepted) {
                    val text = "$locale · $units"
                    ctx.variables.set("${prefix}BandLocale", locale)
                    ctx.variables.set("${prefix}Summary", text)
                    store?.let { ctx.variables.set(it, text) }
                    ctx.logger("Huawei band language set: $text")
                    ActionResult.Success
                } else {
                    // The band answered, and said no. Distinct from a connection failure, and the
                    // distinction matters: this one will not be fixed by trying again.
                    fail(ctx, prefix, store, "the band refused $locale — it may have no pack for it")
                }
            },
            onFailure = { fail(ctx, prefix, store, it.message ?: it::class.java.simpleName) },
        )
    }

    private fun fail(
        ctx: ActionContext,
        prefix: String,
        store: String?,
        why: String,
    ): ActionResult {
        ctx.variables.set("${prefix}Summary", why)
        store?.let { ctx.variables.set(it, why) }
        return ActionResult.Failure(why)
    }
}
