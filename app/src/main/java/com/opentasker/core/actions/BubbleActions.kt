package com.opentasker.core.actions

import com.opentasker.core.bubbles.FlashBubbleStore
import com.opentasker.core.bubbles.FreezeBubbleStore
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * Flash-bubble actions — the workspace's bridge to the 通知明滅 Desktop icon layer
 * ([FlashBubbleStore] / FlashBubbleOverlayManager). The flashing state lives in workspace variables
 * the app can't see, so the 点灯/消灯/全消灯 tasks drive the icons explicitly through these.
 */

/**
 * Add a flash bubble for an app: its icon appears down the screen's LEFT edge while the Desktop is
 * foreground (mirror of the freeze bubbles on the right). New apps stack below existing ones and push
 * the kill-all icon to the bottom.
 *
 * Args:
 *   - "package": package name (usually %NOTIF_PACKAGE)
 *   - "label": optional bubble label; defaults to the app's launcher label
 */
class FlashBubbleAddAction : Action {
    override val id = "bubble.flash_add"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"]?.trim().orEmpty()
        if (pkg.isEmpty()) return ActionResult.Failure("missing package")
        val label = args["label"]?.trim()?.ifBlank { null }
            ?: runCatching {
                val pm = ctx.app.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: pkg.substringAfterLast('.')
        FlashBubbleStore.enqueue(pkg, label)
        ctx.logger("Flash bubble added: $pkg")
        return ActionResult.Success
    }
}

/**
 * Remove one app's flash bubble (no-op if it isn't shown).
 *
 * Args:
 *   - "package": package name (usually %APP_PACKAGE in the 消灯 task)
 */
class FlashBubbleRemoveAction : Action {
    override val id = "bubble.flash_remove"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"]?.trim().orEmpty()
        if (pkg.isEmpty()) return ActionResult.Failure("missing package")
        FlashBubbleStore.remove(pkg)
        ctx.logger("Flash bubble removed: $pkg")
        return ActionResult.Success
    }
}

/** Remove every flash bubble AND the kill-all icon (the 無効 / full-reset path). No args. */
class FlashBubbleClearAction : Action {
    override val id = "bubble.flash_clear"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        FlashBubbleStore.clearAll()
        ctx.logger("Flash bubbles cleared")
        return ActionResult.Success
    }
}

/**
 * Show the kill-all-flashes icon (this app's own icon, pinned below the flash-app bubbles). Tapping
 * it runs the UI-configured kill-all task — same function as tapping the flash-ongoing notification —
 * and hides the icon while keeping the app bubbles. No args.
 */
class FlashKillIconShowAction : Action {
    override val id = "bubble.flashkill_show"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        FlashBubbleStore.showKill()
        return ActionResult.Success
    }
}

/** Hide the kill-all-flashes icon (the app bubbles stay). No args. */
class FlashKillIconHideAction : Action {
    override val id = "bubble.flashkill_hide"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        FlashBubbleStore.hideKill()
        return ActionResult.Success
    }
}

/**
 * Freeze-bubble actions — the workspace's bridge to the 凍結融解 Desktop icon layer
 * ([FreezeBubbleStore] / FreezeBubbleOverlayManager), mirroring the flash ones above.
 *
 * ## What these are for, given the engine already queues bubbles
 *
 * Running a task on 凍結融解's roster queues a bubble by itself — that path needs nothing here. These
 * exist for the cases it cannot reach:
 *
 *  * an app thawed by something that is not a launcher task, which until now only
 *    `ShareForwardActivity` could do, and only from Kotlin;
 *  * retiring a bubble from a task rather than by touching it, so a 無効-style reset can tidy the
 *    Desktop;
 *  * queueing a bubble whose freeze set is not what any task thawed.
 *
 * They are also what makes the layer testable by hand: a bubble can now be put on screen and taken
 * off again without freezing, launching or waiting for a foreground change.
 */

/**
 * Queue a re-freeze bubble for an app.
 *
 * Args:
 *   - "package": the app the bubble is FOR — its icon, its label, its dedupe key
 *   - "label": optional; defaults to the app's own label, read under [AppFreeze.MATCH_FROZEN] because
 *     a bubble is usually queued for an app that is about to be frozen or already is
 *   - "freeze": optional space-separated packages the bubble should re-freeze. Empty means the
 *     bubble's own package, which is what every bubble meant before companions existed.
 *
 * A package in [AppFreeze.PROTECTED] is refused by the store, not here — one gate, in the one place
 * every producer passes through.
 */
class FreezeBubbleAddAction : Action {
    override val id = "bubble.freeze_add"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"]?.trim().orEmpty()
        if (pkg.isEmpty()) return ActionResult.Failure("missing package")
        val label = args["label"]?.trim()?.ifBlank { null }
            ?: runCatching {
                val pm = ctx.app.packageManager
                // MATCH_FROZEN: a frozen app has no label at all under plain flags, and this bubble
                // exists precisely because its app is on its way to being frozen.
                pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, com.opentasker.core.policy.AppFreeze.MATCH_FROZEN),
                ).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: pkg.substringAfterLast('.')
        // Space-separated, the roster convention every package list in this workspace uses.
        val freeze = args["freeze"].orEmpty().split(Regex("""\s+"""))
            .map { it.trim() }.filter { it.isNotEmpty() }
        FreezeBubbleStore.enqueue(pkg, label, iconPath = null, freezePkgs = freeze)
        ctx.logger("Freeze bubble added: $pkg" + if (freeze.size > 1) " (freezes ${freeze.size})" else "")
        return ActionResult.Success
    }
}

/**
 * Remove one app's freeze bubble without freezing it — the long-tap, from a task.
 *
 * A no-op when that app has no bubble, deliberately: a tidy-up task should not have to ask first.
 *
 * Args:
 *   - "package": package name
 */
class FreezeBubbleRemoveAction : Action {
    override val id = "bubble.freeze_remove"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"]?.trim().orEmpty()
        if (pkg.isEmpty()) return ActionResult.Failure("missing package")
        FreezeBubbleStore.remove(pkg)
        ctx.logger("Freeze bubble removed: $pkg")
        return ActionResult.Success
    }
}

/**
 * Drop every pending freeze bubble. Nothing is frozen — the apps stay thawed. No args.
 *
 * The reminders go and the state they were reminding about does not, so this is a "clear the
 * Desktop" action rather than a "give up on re-freezing" one.
 */
class FreezeBubbleClearAction : Action {
    override val id = "bubble.freeze_clear"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        FreezeBubbleStore.clearAll()
        ctx.logger("Freeze bubbles cleared")
        return ActionResult.Success
    }
}
