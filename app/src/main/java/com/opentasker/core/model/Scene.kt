package com.opentasker.core.model

import kotlinx.serialization.Serializable

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
)

@Serializable
data class SceneElement(
    val id: Long = 0,
    val type: SceneElementType,
    val xDp: Int,
    val yDp: Int,
    val widthDp: Int,
    val heightDp: Int,
    val config: Map<String, String> = emptyMap(),
    val tapTaskId: Long? = null,
    val longPressTaskId: Long? = null,
)

@Serializable
enum class SceneElementType {
    BUTTON, TEXT, EDIT_TEXT, CHECKBOX, TOGGLE, SLIDER,
    NUMBER_PICKER, SPINNER, IMAGE, MAP, WEB, MENU, VIDEO,
    OVAL, RECTANGLE, DOODLE,
}
