package com.opentasker.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A floating UI overlay built from elements. */
@Serializable
data class Scene(
    val id: Long = 0,
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val elements: List<SceneElement> = emptyList(),
    val projectId: Long? = null,            // null = Unfiled
    val position: Int = 0,                  // manual sort order within its tab
    val bgColor: String? = null,            // panel background "#AARRGGBB"; null = theme background (black)
    val cornerRadiusDp: Int = 16,           // panel corner radius
    val scrimAlpha: Int = 55,               // modal scrim darkness, 0..100 %
    val borderColor: String? = null,        // panel border "#AARRGGBB"; null = theme outline (yellow)
    val borderWidth: Int = 0,               // panel border thickness dp (0 = none)
    // Default presentation, used by scene.show when the matching arg is omitted (an explicit arg wins).
    val defaultPosition: String = "center", // "top" / "center" / "bottom"
    val defaultModal: Boolean = true,       // true = block the app underneath; false = tap-through HUD
    val defaultDismissOnOutside: Boolean = true, // tap outside (scrim) dismisses a modal scene
)

@Serializable
data class SceneElement(
    val id: Long = 0,
    @Serializable(with = SceneElementTypeSerializer::class)
    val type: SceneElementType,
    val xDp: Int,
    val yDp: Int,
    val widthDp: Int,
    val heightDp: Int,
    val config: Map<String, String> = emptyMap(),
    val tapTaskId: Long? = null,
    val longPressTaskId: Long? = null,
    // Name of the linked task, resolved BEFORE the id (which is only a legacy fallback). A name survives
    // re-imports that re-id the task, and disambiguates same-name tasks by project. Empty = no link / not
    // yet backfilled (older scenes carry only the id; export + the editor populate these going forward).
    val tapTaskName: String = "",
    val longPressTaskName: String = "",
)

/**
 * Fork note (upstream 0.2.88): upstream cut this enum down to the four types ITS overlay draws
 * (BUTTON, TEXT, SLIDER, IMAGE) on the grounds that the other twelve were pickable in name only.
 * That is not true here — the fork's own SceneActivity, SceneElementDrafts and UiEnumLabels render
 * and offer them — so the full set stays. Dropping them would silently rewrite every saved element
 * of those types into a TEXT box on first read.
 */
@Serializable
enum class SceneElementType {
    BUTTON, TEXT, EDIT_TEXT, CHECKBOX, TOGGLE, SLIDER,
    NUMBER_PICKER, SPINNER, IMAGE, MAP, WEB, MENU, VIDEO,
    OVAL, RECTANGLE, DOODLE,
    // A horizontal fill bar: `value` (0..100, usually a %var) fills `fillColor` over `trackColor`;
    // when `charging` is truthy a highlight sweeps along the filled part. Used by the battery line.
    PROGRESS,
    // Neon meteor ribbons orbiting the element's perimeter in a rounded-rect band — the native port
    // LEGACY tombstone (2026-07-16): meteors moved natively into 白い熊 音楽; kept only so
    // archived exports/backups with METEOR elements still decode. Renders nothing; not offered
    // in the editor.
    METEOR,
}

/**
 * Decodes an element type this build does not know as [SceneElementType.TEXT] instead of failing
 * the whole scene.
 *
 * Upstream added this for the twelve types it removed. The fork keeps every one of those, so the
 * fallback only ever fires for a genuinely unknown name — a scene written by a future build, or a
 * hand-edited bundle with a typo — where losing one element beats losing the scene.
 */
object SceneElementTypeSerializer : KSerializer<SceneElementType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.opentasker.core.model.SceneElementType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SceneElementType) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): SceneElementType {
        val raw = decoder.decodeString()
        // Case-insensitive on purpose: the bundle codec decodes enums case-insensitively for
        // hand-edited documents, and that only applies to descriptors of ENUM kind. Matching
        // exactly here would have quietly turned a hand-written "image" into the TEXT fallback
        // instead of an IMAGE the validator checks.
        return SceneElementType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: SceneElementType.TEXT
    }
}
