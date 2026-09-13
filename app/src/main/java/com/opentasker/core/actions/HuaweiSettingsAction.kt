package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiCommands
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner

/**
 * `Huawei Band recording settings` — decide what the band actually measures.
 *
 * ## Why this matters more than it sounds
 *
 * A fresh band has **continuous heart rate and automatic SpO₂ switched OFF**, and a band that is not
 * recording is indistinguishable from a band that cannot. That cost real confusion here: heart rate
 * was missing from every sync until Huawei Health turned `0x07/0x17` on, at which point the band
 * began recording every five minutes. Without this action the only way to set them is to hand the
 * band back to Huawei Health on another phone.
 *
 * Every switch is sent and answered separately, so one refusal does not hide the others and the
 * report says which failed.
 *
 * Each argument takes `on`, `off`, or is left blank to leave that setting alone — blank is not
 * "off", because silently disabling a recorder nobody asked about is the failure this whole feature
 * exists to prevent. The alert arguments take a number to enable with that threshold.
 *
 * ## Two kinds of switch, which is why a row carries its own service
 *
 * `trusleep` / `continuous_hr` / `auto_spo2` and the three alerts are bytes on the fitness service.
 * `apnea` and `stress` are **module features**: a config id written to a JS module on the band over
 * DataSync. They are listed together here because to 白い熊 they are all "what the band records",
 * but they are different wire objects, and a module config sent to the fitness service is ACKed and
 * ignored — see [HuaweiSyncRunner.SettingWrite].
 */
class HuaweiSettingsAction : Action {
    override val id = "huawei.settings"
    override val category = ActionCategory.SYSTEM

    private companion object {
        /**
         * How long a module write waits before its silence is reported as silence.
         *
         * Longer than the 6 s default because the fitness switches are a byte in the band's settings
         * while these reach a JS module, but still bounded: three of them at this figure keep the
         * whole task under half a minute, and the band answers everything it answers at all well
         * inside six seconds.
         */
        const val MODULE_TIMEOUT_MS = 8_000L
    }

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }
        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)

        fun flag(key: String): Boolean? = when (args[key]?.trim()?.lowercase()) {
            "on", "true", "1", "yes" -> true
            "off", "false", "0", "no" -> false
            else -> null                    // absent or unrecognised: leave the band alone
        }

        /** An alert argument: a number enables with that threshold, "off" disables, blank leaves. */
        fun alert(key: String): Pair<Boolean, Int>? {
            val v = args[key]?.trim()?.lowercase()?.ifEmpty { null } ?: return null
            v.toIntOrNull()?.let { return true to it }
            return if (v in setOf("off", "false", "0", "no")) false to 0 else null
        }

        /** A switch on the fitness service — a byte on 0x07. */
        fun fit(name: String, command: Int, payload: ByteArray) =
            HuaweiSyncRunner.SettingWrite(name, HuaweiCommands.SVC_FITNESS, command, payload)

        /**
         * A module feature — a config id written over DataSync 0x37, not a fitness byte.
         *
         * Allowed to go unacknowledged, because this service never answers and the write works
         * anyway — confirmed on the band's own screens on 2026-09-12, after three writes that every
         * wire-level signal called a timeout. See [HuaweiSyncRunner.SettingWrite.unacknowledged].
         */
        fun module(name: String, configId: Int, on: Boolean) =
            HuaweiSyncRunner.SettingWrite(
                name, HuaweiCommands.SVC_DATA_SYNC, HuaweiCommands.DATA_SYNC_CONFIG,
                HuaweiCommands.moduleConfig(configId, on),
                timeoutMs = MODULE_TIMEOUT_MS,
                unacknowledged = true,
            )

        val toggles = buildList {
            flag("trusleep")?.let {
                add(fit("truSleep", HuaweiCommands.FIT_TRUSLEEP, HuaweiCommands.fitnessToggle(it)))
            }
            flag("continuous_hr")?.let {
                add(fit("continuous heart rate", HuaweiCommands.FIT_CONTINUOUS_HR, HuaweiCommands.fitnessToggle(it)))
            }
            flag("auto_spo2")?.let {
                add(fit("automatic SpO2", HuaweiCommands.FIT_AUTO_SPO2, HuaweiCommands.fitnessToggle(it)))
            }
            // Both ids, together — see [HuaweiCommands.MODULE_APNEA]. Numbered in the name so a
            // half-failure reads as "the second one was refused" rather than as one ambiguous row.
            flag("apnea")?.let { on ->
                HuaweiCommands.MODULE_APNEA.forEachIndexed { i, id ->
                    add(module("sleep breathing awareness ${i + 1}/2", id, on))
                }
            }
            flag("stress")?.let { add(module("emotions / stress", HuaweiCommands.MODULE_EMOTION, it)) }
            alert("high_hr")?.let { (on, t) ->
                add(fit("high heart-rate alert", HuaweiCommands.FIT_HIGH_HR_ALERT, HuaweiCommands.fitnessAlert(on, t)))
            }
            alert("low_hr")?.let { (on, t) ->
                add(fit("low heart-rate alert", HuaweiCommands.FIT_LOW_HR_ALERT, HuaweiCommands.fitnessAlert(on, t)))
            }
            alert("low_spo2")?.let { (on, t) ->
                add(fit("low SpO2 alert", HuaweiCommands.FIT_LOW_SPO2_ALERT, HuaweiCommands.fitnessAlert(on, t)))
            }
        }
        if (toggles.isEmpty()) {
            return fail(ctx, prefix, store, "nothing to set — every setting was left blank")
        }

        return HuaweiSyncRunner.applySettings(ctx.app, address, toggles).fold(
            onSuccess = { rows ->
                // An unconfirmed row prints its own wording rather than "set". The whole point of
                // the third state is that it must not read as the first one.
                val text = rows.joinToString(" · ") {
                    "${it.name}: ${if (it.ok && it.confirmed) "set" else it.detail}"
                }
                ctx.variables.set("${prefix}Summary", text)
                store?.let { ctx.variables.set(it, text) }
                ctx.logger("Huawei settings: $text")
                if (rows.all { it.ok }) ActionResult.Success else ActionResult.Failure(text)
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
