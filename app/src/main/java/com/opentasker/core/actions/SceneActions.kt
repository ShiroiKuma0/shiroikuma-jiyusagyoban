package com.opentasker.core.actions

import android.content.Intent
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.variables.expandAgainstGlobals
import com.opentasker.core.model.Scene
import com.opentasker.core.storage.SceneDao
import com.opentasker.core.storage.SceneEntity
import com.opentasker.scenes.SceneActivity
import com.opentasker.scenes.SceneOverlayManager
import com.opentasker.scenes.WakedanceActivity

/**
 * Resolve a scene reference (from `scene.show` / `scene.hide`) to a stored scene.
 *
 * Scenes are linked by **name**, keyed by `(project, name)` — a name is unique within a project, but
 * the same name may exist in different projects. So a reference resolves, in order:
 *   1. a scene with that name in the **caller's project** (the common case — survives re-imports that
 *      re-id the scene, and disambiguates same-name scenes across projects);
 *   2. otherwise any scene with that name (cross-project show), chosen deterministically (lowest
 *      position, then id) so it never flips between equally-named candidates;
 *   3. finally, a purely-numeric ref as a raw id — back-compat only; a real name always wins.
 *
 * [callerProjectId] is the running task's project (0 = Unfiled/super); a scene's null projectId is
 * also Unfiled, so the two are compared as `(projectId ?: 0)`.
 */
internal suspend fun resolveScene(dao: SceneDao, ref: String, callerProjectId: Long): SceneEntity? {
    val all = dao.getAll()
    all.firstOrNull { (it.projectId ?: 0L) == callerProjectId && it.name.equals(ref, ignoreCase = true) }
        ?.let { return it }
    all.filter { it.name.equals(ref, ignoreCase = true) }
        .minByOrNull { it.position.toLong() * 10_000_000L + it.id }
        ?.let { return it }
    return ref.toLongOrNull()?.let { id -> all.firstOrNull { it.id == id } }
}

/** Expand %vars in the scene's PANEL colors (bg / border) against the persisted globals at show time.
 *  Element configs are deliberately left RAW: `SceneElementView.v()` re-reads + expands them LIVE on
 *  every variable change (the 電池線 battery line / charge sweep, live text, …). Pre-expanding the
 *  element configs here froze them to their show-time snapshot — the cause of stale battery/clock. */
private fun Scene.withGlobalsExpanded(): Scene = copy(
    bgColor = bgColor?.let { expandAgainstGlobals(it) },
    borderColor = borderColor?.let { expandAgainstGlobals(it) },
)

/**
 * `Show Scene` — display a scene (by name or id). With the "Display over other apps" permission it
 * shows as a system-wide overlay (works over other apps and from background triggers); without it,
 * it falls back to a foreground Activity (only visible while this app is in front).
 */
class ShowSceneAction : Action {
    override val id = "scene.show"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val ref = args["scene"]?.trim().orEmpty()
        if (ref.isEmpty()) return ActionResult.Failure("missing scene name")
        val dao = OpenTaskerApp_NoHilt.db.sceneDao()
        val entity = resolveScene(dao, ref, ctx.variables.projectId)
            ?: return ActionResult.Failure("scene not found: \"$ref\"")
        val scene = entity.toDomain().withGlobalsExpanded()
        // Resolution for the presentation flags: an explicit arg wins; otherwise fall back to the
        // scene's own remembered default (set in the editor).
        fun boolArg(key: String): Boolean? = args[key]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?.let { it !in setOf("false", "0", "off", "no") }
        val position = args["position"]?.trim()?.lowercase()?.ifBlank { null } ?: scene.defaultPosition
        val modal = boolArg("modal") ?: scene.defaultModal
        val timeoutMs = (args["timeout"]?.trim()?.toDoubleOrNull()?.times(1000))?.toLong()?.coerceAtLeast(0L) ?: 0L
        val dismissOnOutside = boolArg("dismissOnOutside") ?: scene.defaultDismissOnOutside
        // fullWidth: span the screen (status-bar style); ignored for modal scenes. Used by the battery line.
        val fullWidth = boolArg("fullWidth") ?: false
        // fullscreen: cover the whole screen, fully tap-through (a purely visual overlay). The music edge-light.
        val fullscreen = boolArg("fullscreen") ?: false
        // edgeCenter: for a left/right overlay, sit vertically centred (default left/right drops lower for media HUDs).
        val edgeCenter = boolArg("edgeCenter") ?: false
        // inset: dp to pull a left/right overlay in from the very edge (clears the OEM edge-gesture region).
        val insetDp = args["inset"]?.trim()?.toIntOrNull() ?: 0
        // heightFraction: 0..1 of the screen height (re-sized on fold/rotation). Used by the edge strips.
        val heightFraction = args["heightFraction"]?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        // vAlign: top/center/bottom — which third a left/right edge strip sits in.
        val vAlign = args["vAlign"]?.trim()?.lowercase()?.ifBlank { null }
        // widthFraction + hAlign: a bottom edge strip's width (0..1 of the screen) and which third (left/center/right).
        val widthFraction = args["widthFraction"]?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        val hAlign = args["hAlign"]?.trim()?.lowercase()?.ifBlank { null }
        // showWhenLocked: render over the lockscreen without unlocking (the tsuchi wakedance).
        val showWhenLocked = boolArg("showWhenLocked") ?: false
        // keepScreenOn: the overlay window blocks the screen timeout while shown (音楽 buttons).
        val keepScreenOn = boolArg("keepScreenOn") ?: false
        if (showWhenLocked) {
            // Over the lockscreen: an Activity with setShowWhenLocked is the path EMUI honours (the
            // accessibility overlay sits UNDER the keyguard there, and FLAG_SHOW_WHEN_LOCKED on it is
            // ignored). Renders the scene fullscreen + wakes the screen; auto-dismisses on the timeout.
            val intent = Intent(ctx.app, WakedanceActivity::class.java).apply {
                putExtra(SceneActivity.EXTRA_SCENE_ID, entity.id)
                putExtra(SceneActivity.EXTRA_SHOW_WHEN_LOCKED, true)
                putExtra(SceneActivity.EXTRA_FULLSCREEN, true)
                if (timeoutMs > 0) putExtra(SceneActivity.EXTRA_TIMEOUT_MS, timeoutMs)
                // Opaque WakedanceActivity self-finishes at the end of each pulse, so it's gone before the
                // next — a fresh instance without CLEAR_TASK (which revealed the launcher/wallpaper).
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            ctx.app.startActivity(intent)
            ctx.logger("Show scene \"${scene.name}\" over lockscreen (Activity)")
        } else if (SceneOverlayManager.canOverlay(ctx.app)) {
            SceneOverlayManager.show(ctx.app, scene, position, modal, timeoutMs, dismissOnOutside, fullWidth, fullscreen, edgeCenter, insetDp, heightFraction, vAlign, widthFraction, hAlign, showWhenLocked, keepScreenOn)
            ctx.logger("Show scene \"${scene.name}\" (overlay, ${if (modal) "modal" else "tap-through"})")
        } else {
            val intent = Intent(ctx.app, SceneActivity::class.java).apply {
                putExtra(SceneActivity.EXTRA_SCENE_ID, entity.id)
                position?.let { putExtra(SceneActivity.EXTRA_POSITION, it) }
                if (timeoutMs > 0) putExtra(SceneActivity.EXTRA_TIMEOUT_MS, timeoutMs)
                putExtra(SceneActivity.EXTRA_DISMISS_OUTSIDE, dismissOnOutside)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.app.startActivity(intent)
            ctx.logger("Show scene \"${scene.name}\" (foreground; grant Display over other apps for system-wide)")
        }
        return ActionResult.Success
    }
}

/**
 * `Hide Scene` — dismiss shown scenes. With no args, hides every scene; with a `scene` arg
 * (name or id), hides just that one (so e.g. hiding the music edge-light leaves the battery line up).
 */
class HideSceneAction : Action {
    override val id = "scene.hide"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val ref = args["scene"]?.trim().orEmpty()
        if (ref.isNotEmpty()) {
            val dao = OpenTaskerApp_NoHilt.db.sceneDao()
            val entity = resolveScene(dao, ref, ctx.variables.projectId)
                ?: return ActionResult.Failure("scene not found: \"$ref\"")
            SceneOverlayManager.hide(entity.id)
            ctx.logger("Hid scene \"${entity.toDomain().name}\"")
            return ActionResult.Success
        }
        val n = SceneOverlayManager.hideAll() + SceneActivity.dismissAll()
        ctx.logger("Hid $n scene(s)")
        return ActionResult.Success
    }
}

// ---------------------------------------------------------------------------------------------
// scene.gestures — what is bound to what.
//
// The edge bars (画面操作) are invisible strips whose whole content is their gesture bindings, and
// those bindings live only in each scene element's free-form config map: nothing in the app ever
// SHOWS them, and there is no editor field for them either. So the only way to know which swipe on
// which bar runs which task was to read an export. This action reads them back out of the database,
// which is what makes a printed reference sheet worth having: it cannot go stale, because it is
// generated from the same rows the gesture detector reads.
// ---------------------------------------------------------------------------------------------

/** Gesture config keys, in the order a sheet should list them, with the label in each language. */
private val GESTURE_LABELS: List<Triple<String, String, String>> = listOf(
    Triple("tap", "タップ", "Tap"),
    Triple("doubleTap", "ダブルタップ", "Double tap"),
    Triple("longPress", "長押し", "Long press"),
    Triple("swipeUp", "スワイプ ↑", "Swipe ↑"),
    Triple("swipeDown", "スワイプ ↓", "Swipe ↓"),
    Triple("swipeLeft", "スワイプ ←", "Swipe ←"),
    Triple("swipeRight", "スワイプ →", "Swipe →"),
    Triple("longSwipeUp", "ロングスワイプ ↑", "Long swipe ↑"),
    Triple("longSwipeDown", "ロングスワイプ ↓", "Long swipe ↓"),
    Triple("longSwipeLeft", "ロングスワイプ ←", "Long swipe ←"),
    Triple("longSwipeRight", "ロングスワイプ →", "Long swipe →"),
    Triple("moveDebug", "初動（デバッグ）", "First move (debug)"),
)

/** `tap` and `longPress` are element columns, not config keys — everything else lives in the map. */
private val GESTURE_CONFIG_KEYS: Set<String> =
    GESTURE_LABELS.map { it.first }.toSet() - setOf("tap", "longPress")

/**
 * Every gesture bound on [element], as (printable gesture, task name) in [GESTURE_LABELS] order.
 *
 * A binding is stored as a task NAME (current) or a legacy id string, exactly as the gesture detector
 * accepts both; an id is resolved through [taskNameById] so the sheet never prints a bare number, and
 * an id that no longer resolves is called out rather than silently dropped — a dangling binding is
 * precisely the thing a reference sheet should expose.
 */
private fun gesturesOf(
    element: com.opentasker.core.model.SceneElement,
    taskNameById: Map<Long, String>,
    lang: SheetLang,
): List<Pair<String, String>> {
    fun taskName(ref: String?): String? {
        val trimmed = ref?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val id = trimmed.toLongOrNull() ?: return trimmed
        return taskNameById[id] ?: ("#$id " + lang.of("（見つからない）", "(not found)"))
    }
    val bound = buildMap {
        taskName(element.tapTaskName.ifBlank { element.tapTaskId?.toString().orEmpty() })
            ?.let { put("tap", it) }
        taskName(element.longPressTaskName.ifBlank { element.longPressTaskId?.toString().orEmpty() })
            ?.let { put("longPress", it) }
        element.config.forEach { (key, value) ->
            if (key in GESTURE_CONFIG_KEYS) taskName(value)?.let { put(key, it) }
        }
    }
    return GESTURE_LABELS.mapNotNull { (key, ja, en) -> bound[key]?.let { lang.of(ja, en) to it } }
}

/** One heading of the sheet and the scenes filed under it. A null heading = an unheaded flat list. */
private data class GestureSection(val heading: String?, val sceneRefs: List<String>)

/**
 * Read the `scenes` argument.
 *
 * `右辺|Right edge: 右上, 右中, 右下` on its own line opens a section; a line with no colon is a plain
 * list of scenes with no heading, which is also what a single comma-separated line means. The heading
 * may carry both languages, `日本語|English`. Heading and membership both have to be spelled out
 * because neither is stored: the app groups the edge scenes as 辺/右辺/… in the Scenes tab, but that
 * tree carries the tab's own ordering, not the order the bars should be READ in (right, bottom, left).
 */
private fun parseGestureSections(raw: String, lang: SheetLang): List<GestureSection> =
    raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val cut = line.indexOfFirst { it == ':' || it == '：' }
        val heading = if (cut > 0) lang.pick(line.substring(0, cut)).takeIf { it.isNotEmpty() } else null
        val list = if (cut > 0) line.substring(cut + 1) else line
        GestureSection(heading, list.split(',', '、').map { it.trim() }.filter { it.isNotEmpty() })
    }.filter { it.sceneRefs.isNotEmpty() }

/**
 * `Scene Gestures` — write a ready-to-show listing of which gesture on which scene runs which task.
 *
 * Scenes with no gesture at all are skipped, so an unused edge bar never takes up a heading — and a
 * section whose scenes are ALL empty prints no heading either. The text is emitted in `dialog.text`'s
 * markup, so `scene.gestures` → `dialog.text (markup, size=full)` is the whole reference sheet.
 */
class SceneGesturesAction : Action {
    override val id = "scene.gestures"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val store = args["store"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() } ?: "gestures"
        val lang = sheetLangOf(args["lang"])
        val sceneDao = OpenTaskerApp_NoHilt.db.sceneDao()
        val taskNameById = OpenTaskerApp_NoHilt.db.taskDao().getAll().associate { it.id to it.name }
        // An explicit list is both the filter AND the order. With none given, fall back to every scene
        // in the caller's project, in the order the Scenes tab shows them.
        val sections = parseGestureSections(args["scenes"].orEmpty(), lang).ifEmpty {
            listOf(
                GestureSection(
                    heading = null,
                    sceneRefs = sceneDao.getAll()
                        .filter { (it.projectId ?: 0L) == ctx.variables.projectId }
                        .sortedWith(compareBy({ it.position }, { it.name }))
                        .map { it.name },
                ),
            )
        }
        // A heading anywhere pushes the scene names one level down, so the sides read as the top level.
        val sceneLevel = if (sections.any { it.heading != null }) 3 else 2

        val out = StringBuilder()
        var listed = 0
        for (section in sections) {
            val scenes: List<Scene> = section.sceneRefs
                .mapNotNull { resolveScene(sceneDao, it, ctx.variables.projectId) }
                .distinctBy { it.id }.map { it.toDomain() }
            // Built into a buffer first: a side with nothing bound on any of its bars must not leave a
            // bare heading behind.
            val body = StringBuilder()
            for (scene in scenes) {
                val blocks = scene.elements.mapIndexedNotNull { index, element ->
                    gesturesOf(element, taskNameById, lang).takeIf { it.isNotEmpty() }?.let { gestures ->
                        val label = element.config["label"]?.trim()?.takeIf { it.isNotEmpty() }
                            ?: lang.of("要素 ${index + 1}", "Element ${index + 1}")
                        label to gestures
                    }
                }
                if (blocks.isEmpty()) continue
                listed++
                body.append("#".repeat(sceneLevel)).append(' ').append(scene.name).append('\n')
                for ((label, gestures) in blocks) {
                    // A single-element scene (every edge bar) is its own heading already; only a panel
                    // with several gesture-bearing elements needs to say which one it is talking about.
                    if (blocks.size > 1) {
                        body.append("#".repeat(sceneLevel + 1)).append(' ').append(label).append('\n')
                    }
                    gestures.forEach { (gesture, task) ->
                        body.append("**").append(gesture).append("** → __").append(task).append("__\n")
                    }
                }
                body.append('\n')
            }
            if (body.isEmpty()) continue
            section.heading?.let { out.append("## ").append(it).append('\n') }
            out.append(body)
        }
        if (listed == 0) {
            out.append(
                lang.pick(args["empty_text"]).takeIf { it.isNotEmpty() }
                    ?: lang.of("ジェスチャーは登録されていません。", "No gesture is bound."),
            )
        }
        appendSheetFooter(out, args["footer"], lang, listed)

        ctx.variables.set(store, out.toString().trimEnd())
        ctx.variables.set("${store}_count", listed.toString())
        ctx.variables.set("${store}_title", lang.pick(args["title"]))
        ctx.logger("Gesture sheet: $listed scene(s) → %$store")
        return ActionResult.Success
    }
}
