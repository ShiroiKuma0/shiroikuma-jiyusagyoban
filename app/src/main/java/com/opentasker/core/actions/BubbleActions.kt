package com.opentasker.core.actions

import com.opentasker.core.bubbles.FlashBubbleStore
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
