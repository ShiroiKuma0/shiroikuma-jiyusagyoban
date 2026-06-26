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
    // Default presentation, used by scene.show when the matching arg is omitted (an explicit arg wins).
    val defaultPosition: String = "center", // "top" / "center" / "bottom"
    val defaultModal: Boolean = true,       // true = block the app underneath; false = tap-through HUD
    val defaultDismissOnOutside: Boolean = true, // tap outside (scrim) dismisses a modal scene
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
    // Name of the linked task, resolved BEFORE the id (which is only a legacy fallback). A name survives
    // re-imports that re-id the task, and disambiguates same-name tasks by project. Empty = no link / not
    // yet backfilled (older scenes carry only the id; export + the editor populate these going forward).
    val tapTaskName: String = "",
    val longPressTaskName: String = "",
)

@Serializable
enum class SceneElementType {
    BUTTON, TEXT, EDIT_TEXT, CHECKBOX, TOGGLE, SLIDER,
    NUMBER_PICKER, SPINNER, IMAGE, MAP, WEB, MENU, VIDEO,
    OVAL, RECTANGLE, DOODLE,
    // A horizontal fill bar: `value` (0..100, usually a %var) fills `fillColor` over `trackColor`;
    // when `charging` is truthy a highlight sweeps along the filled part. Used by the battery line.
    PROGRESS,
}
