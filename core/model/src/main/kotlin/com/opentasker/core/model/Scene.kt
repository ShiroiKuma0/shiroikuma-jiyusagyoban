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
    val projectId: Long = DEFAULT_PROJECT_ID,
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
)

/**
 * Only the types the overlay can actually draw.
 *
 * Twelve more were declared (EDIT_TEXT, CHECKBOX, TOGGLE, NUMBER_PICKER, SPINNER, MAP, WEB, MENU,
 * VIDEO, OVAL, RECTANGLE, DOODLE) but nothing ever rendered them: the editor refused to create
 * them and the overlay drew a grey "unsupported" label. They were removed rather than translated.
 */
@Serializable
enum class SceneElementType {
    BUTTON, TEXT, SLIDER, IMAGE,
}

/**
 * Decodes a removed element type as [SceneElementType.TEXT] instead of failing the whole scene.
 *
 * A scene saved by an older build can name one of the twelve removed types. The element keeps its
 * position, size and task bindings; its leftover config keys are ignored, and saving the scene
 * again writes the fallback, so the migration is one-way and happens on first read.
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
