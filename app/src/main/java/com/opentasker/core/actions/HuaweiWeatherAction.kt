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
            ?: if (args["raw"]?.trim().isNullOrEmpty()) return fail(ctx, prefix, store, "no temperature given") else 0
        val humidity = args["humidity"]?.trim()?.toDoubleOrNull()?.toInt()
        val high = args["high"]?.trim()?.toDoubleOrNull()?.toInt()
        val low = args["low"]?.trim()?.toDoubleOrNull()?.toInt()
        val lat = args["latitude"]?.trim()?.toDoubleOrNull()
        val lon = args["longitude"]?.trim()?.toDoubleOrNull()
        val uv = args["uv"]?.trim()?.toDoubleOrNull()?.toInt()
        // 天気 computes this from its own cached hours' offset rather than from the clock, so a
        // half-hour zone (India, Nepal) lands on :30 instead of being snapped half an hour wrong.
        // Trust it over our own flooring whenever it is given.
        val hourStart = args["hourly_start"]?.trim()?.toLongOrNull()
        val wind = args["wind"]?.trim()?.toDoubleOrNull()?.toInt()

        // The series, in the plainest form a task can build from a weather app's reply.
        //
        // Parallel comma-separated arrays, matching the 天気 QUERY_WEATHER contract key for key,
        // so a task can pass %Huawei_Tenki_hourly_temperature straight through with no reshaping.
        // Element i means the same hour (or day) in every array of its group.
        // POSITION IS MEANING. 天気 sends honestly sparse arrays — a three-hourly source fills
        // elements 0, 3, 6 and leaves the rest empty rather than inventing the hours between. So an
        // empty element is a GAP, and dropping it would slide every later hour earlier: hour 9 would
        // be drawn at hour 3 and tomorrow's reading at hour 8, plausibly and wrongly. Parse to
        // nullable and keep the length.
        fun sparse(key: String): List<Int?> = args[key]?.trim()?.ifEmpty { null }
            ?.split(',')?.map { it.trim().toDoubleOrNull()?.toInt() } ?: emptyList()
        fun words(key: String): List<String> = args[key]?.trim()?.ifEmpty { null }
            ?.split(',')?.map { it.trim() } ?: emptyList()
        fun ints(key: String): List<Int> = sparse(key).filterNotNull()

        /**
         * Close the gaps by carrying the nearest known reading, keeping every position.
         *
         * The band will not take a short list, so the gaps have to be filled with something; the
         * last real reading is the only value here that is not an invention. A leading gap takes the
         * first real one. How many were real is reported back so a carried value never passes for a
         * measured one.
         */
        fun carry(v: List<Int?>): List<Int> {
            var last = v.firstOrNull { it != null } ?: return emptyList()
            return v.map { x -> if (x != null) { last = x; x } else last }
        }

        // FIRST element = the hour we are in. The band takes 24 hourly entries or refuses the whole
        // record, so a shorter list is stretched by repeating the last value — see padHours.
        val hourlyRaw = sparse("hourly")
        val hourlyTemp = carry(hourlyRaw)
        val hourlyUv = carry(sparse("hourly_uv"))
        val hourlyCond = words("hourly_condition")
        val hourlyFeels = carry(sparse("hourly_feels"))
        val hourly = hourlyTemp.mapIndexed { i, t ->
            com.opentasker.core.huawei.HuaweiCommands.HourlyPoint(
                // Restamped by padHours; the caller never has to find the hour boundary itself.
                epochSeconds = 0L,
                temperatureC = t,
                condition = com.opentasker.core.huawei.HuaweiCommands
                    .conditionCode(hourlyCond.getOrNull(i)),
                uvIndex = hourlyUv.getOrElse(i) { 0 },
                feelsLikeC = hourlyFeels.getOrElse(i) { t },
            )
        }

        // FIRST element = today. `daily` stays as the hand-written form (one day per `;`, each
        // `high/low` or `high/low/condition`); the three parallel arrays are the 天気 form.
        val dailyHigh = carry(sparse("daily_high"))
        val dailyLow = carry(sparse("daily_low"))
        val dailyCond = words("daily_condition")
        val daily = if (dailyHigh.isNotEmpty()) {
            dailyHigh.indices.mapNotNull { i ->
                val lo = dailyLow.getOrNull(i) ?: return@mapNotNull null
                Triple(
                    dailyHigh[i], lo,
                    com.opentasker.core.huawei.HuaweiCommands.conditionCode(dailyCond.getOrNull(i)),
                )
            }
        } else {
            args["daily"]?.trim()?.ifEmpty { null }?.split(';')
                ?.mapNotNull { entry ->
                    val f = entry.trim().split('/').mapNotNull { it.trim().toDoubleOrNull()?.toInt() }
                    if (f.size < 2) null else Triple(f[0], f[1], f.getOrElse(2) { 1 })
                } ?: emptyList()
        }

        // Diagnostics for the display bug, all off by default.
        //
        // `raw` sends the given hex as the push payload verbatim, bypassing every field above. It
        // exists because our push differs from Health's captured one in five ways at once and the
        // capture has no negative control: composing a "corrected" payload from five simultaneous
        // guesses is how a fix gets believed without being tested. Replaying Health's own bytes
        // tests the shape alone, and then each field can be put back one at a time by editing the
        // hex — no rebuild per step.
        val raw = args["raw"]?.trim()?.ifEmpty { null }?.replace(" ", "")?.let { hex ->
            runCatching { ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }
                .getOrElse { return fail(ctx, prefix, store, "raw is not hex: ${it.message}") }
        }
        val rawForecast = args["raw_forecast"]?.trim()?.ifEmpty { null }?.replace(" ", "")?.let { hex ->
            runCatching { ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }
                .getOrElse { return fail(ctx, prefix, store, "raw_forecast is not hex: ${it.message}") }
        }
        val enableFirst = args["enable"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
        val readBack = args["read_back"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
        // Health's three reads, in Health's position — ahead of the push. See HuaweiCommands.WEATHER_READS.
        val preReads = args["pre_reads"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

        // Timestamps are restamped by padDays, so zero here rather than a value that would have
        // to be kept in step with the runner's own day marker.
        val days = daily.map { (hi, lo, cond) ->
            com.opentasker.core.huawei.HuaweiCommands.DailyPoint(
                epochSeconds = 0L, highC = hi, lowC = lo, condition = cond,
            )
        }

        return HuaweiSyncRunner.pushWeather(
            ctx.app, address, place, temp, humidity, high, low, lat, lon,
            uvIndex = uv, windKmh = wind, hourlyPoints = hourly, dailyDays = days,
            hourStartOverride = hourStart,
            realHourly = hourlyRaw.count { it != null },
            realDaily = sparse("daily_high").count { it != null },
            rawPayload = raw, rawForecast = rawForecast, enableFirst = enableFirst, readBack = readBack,
            preReads = preReads,
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
