package com.opentasker.core.gengoshima

import android.content.Context
import com.opentasker.core.claude.ClaudeClient
import com.opentasker.core.engine.VariableStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 言語島's settings, read from `日本語の設定 -- [227][01]`.
 *
 * They are `%Gengoshima_*` variables — MixedCase, so they belong to the 日本語 project — and a
 * project variable can only be read from inside one of that project's tasks. The entry screen,
 * though, starts generation by itself when it closes, long after the task that opened it has ended.
 * So every 言語島 action reads the settings where it can ([from]) and [remember]s them, and the
 * generation that runs later uses the last copy ([last]). Changing a setting therefore takes effect
 * the next time any 言語島 task runs — which the settings task's own 71 caller does.
 *
 * Every default below is the layout and voice the plan settled on; the settings task writes them
 * all out explicitly anyway, so nothing here is invisible.
 */
@Serializable
data class GengoshimaSettings(
    val apiKey: String = "",
    val model: String = ClaudeClient.DEFAULT_MODEL,
    val effort: String = "high",
    val speaker: String = "31",
    val speed: String = "1.15",
    val pitch: String = "0",
    val intonation: String = "1.0",
    val gap: String = "0.25",
    val bitrateKbps: String = "32",
    val shadowRepeats: Int = 5,
    val shadowPauseFactor: Double = 1.2,
    val dir: String = "/sdcard/〇/[227] 日本語/[227][727] 言語島",
    val islandDirPattern: String = "{no} {name_ja} — {name_en}",
    val sentenceFilePattern: String = "{no} {ja}",
    val islandFileName: String = "000 島全体",
    val numberWidth: Int = 3,
    val nameMaxChars: Int = 40,
) {
    /** What decides a sentence's audio besides its text: any change here re-voices it. */
    val voiceKey: String get() = "$speaker|$speed|$pitch|$intonation|$gap|$bitrateKbps"

    companion object {
        private const val PREFS = "gengoshima"
        private const val KEY = "settings"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Read `%Gengoshima_*` as the running task sees them; blank or absent keeps the default. */
        fun from(vars: VariableStore): GengoshimaSettings {
            val d = GengoshimaSettings()
            fun v(name: String) = vars.get("Gengoshima_$name")?.trim().orEmpty()
            fun s(name: String, def: String) = v(name).ifEmpty { def }
            fun i(name: String, def: Int) = v(name).toIntOrNull() ?: def
            return GengoshimaSettings(
                apiKey = v("ApiKey"),
                model = s("Model", d.model),
                effort = s("Effort", d.effort),
                speaker = s("Speaker", d.speaker),
                speed = s("Speed", d.speed),
                pitch = s("Pitch", d.pitch),
                intonation = s("Intonation", d.intonation),
                gap = s("Gap", d.gap),
                bitrateKbps = s("OpusKbps", d.bitrateKbps),
                shadowRepeats = i("ShadowRepeats", d.shadowRepeats),
                shadowPauseFactor = v("ShadowPauseFactor").toDoubleOrNull() ?: d.shadowPauseFactor,
                dir = s("Dir", d.dir).trimEnd('/'),
                islandDirPattern = s("IslandDirPattern", d.islandDirPattern),
                sentenceFilePattern = s("SentenceFilePattern", d.sentenceFilePattern),
                islandFileName = s("IslandFileName", d.islandFileName),
                numberWidth = i("NumberWidth", d.numberWidth).coerceIn(1, 6),
                nameMaxChars = i("NameMaxChars", d.nameMaxChars).coerceIn(4, 120),
            )
        }

        fun remember(context: Context, settings: GengoshimaSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, json.encodeToString(serializer(), settings))
                .apply()
        }

        fun last(context: Context): GengoshimaSettings =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
                ?: GengoshimaSettings()
    }
}
