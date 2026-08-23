package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner

/**
 * `Huawei Band weather` — put weather on the band's screen.
 *
 * The band does not fetch weather. It displays whatever the phone last pushed, which means the
 * source is entirely 白い熊's choice: any task that can obtain a temperature can drive this, with no
 * Huawei account and nothing of ours acting as the band's web client.
 *
 * That last point is deliberate. The band repeatedly asks the phone to fetch arbitrary URLs on its
 * behalf (`hw.wearable.httpProxy`), and Huawei Health does not answer it either. Weather does not
 * need it, so we do not offer it — becoming a device's general-purpose HTTP client is a much larger
 * thing to agree to than showing it a temperature.
 *
 * The condition/icon codes are NOT sent: one capture cannot pin small integers with no anchor, and a
 * confidently wrong icon is worse than none. Temperature and place display fine without them.
 */
class HuaweiWeatherAction : Action {
    override val id = "huawei.weather"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }
        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)

        val place = args["place"]?.trim()?.ifEmpty { null } ?: "—"
        val temp = args["temperature"]?.trim()?.toDoubleOrNull()?.toInt()
            ?: return fail(ctx, prefix, store, "no temperature given")
        val humidity = args["humidity"]?.trim()?.toDoubleOrNull()?.toInt()
        val high = args["high"]?.trim()?.toDoubleOrNull()?.toInt()
        val low = args["low"]?.trim()?.toDoubleOrNull()?.toInt()
        val lat = args["latitude"]?.trim()?.toDoubleOrNull()
        val lon = args["longitude"]?.trim()?.toDoubleOrNull()

        return HuaweiSyncRunner.pushWeather(
            ctx.app, address, place, temp, humidity, high, low, lat, lon,
        ).fold(
            onSuccess = { text ->
                ctx.variables.set("${prefix}Summary", text)
                store?.let { ctx.variables.set(it, text) }
                ctx.logger("Huawei weather: $text")
                ActionResult.Success
            },
            onFailure = { fail(ctx, prefix, store, it.message ?: it::class.java.simpleName) },
        )
    }

    private fun fail(ctx: ActionContext, prefix: String, store: String?, why: String): ActionResult {
        ctx.variables.set("${prefix}Summary", why)
        store?.let { ctx.variables.set(it, why) }
        return ActionResult.Failure(why)
    }
}
