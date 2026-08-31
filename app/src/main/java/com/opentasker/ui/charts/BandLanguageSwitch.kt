package com.opentasker.ui.charts

import android.content.Context
import com.opentasker.core.band.BandSettings
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity

/**
 * The other half of the 日本語／英語 pill: switching the language **through the settings task**.
 *
 * The obvious implementation is one line — `BandSettings.setLanguage(app, tag)` — and it is the wrong
 * one. `%Band_Language` is defined in `健康の設定 -- [727][01]`, and that task is what the workspace,
 * the mirror and 白い熊 all treat as the setting. A pill that wrote the preference directly would
 * leave the task still saying `en-US` while the window displayed Japanese, and the next run of the
 * task — which happens on every 起動完了 — would silently undo the switch. Whichever of the two you
 * believed, the other would be lying.
 *
 * So the pill does what 白い熊 would do by hand (2026-08-20), in the same order: rewrite the value in
 * the task, save the task, run the task. Running it is not a flourish — `var.set` is what publishes
 * the global, and the task's closing `flash` is the confirmation, neither of which happens if the row
 * is merely updated in the database.
 *
 * The settings task sets variables and nothing else, though: `band.charts` — the action that persists
 * the preference [BandChartsActivity] reads at launch — lives in `健康 -- [727]`, the opener. So the
 * switch writes that preference itself, with exactly the value the task just published. Miss this and
 * the pill appears to work until the system recreates the window, which then comes back up in the old
 * language with the task insisting otherwise.
 *
 * Pure functions first so the rewrite is testable without a device; only [switchTo] touches Room.
 */
object BandLanguageSwitch {

    /** The global the settings task defines, and the window's language follows. */
    const val VARIABLE = "Band_Language"

    /** Tie-breaker only — the task is found by what it *does*, not by what it is called. */
    const val SETTINGS_TASK = "健康の設定 -- [727][01]"

    private const val VAR_SET = "var.set"

    /** There are two languages, so "switch" needs no argument. */
    fun other(lang: BandLanguage): BandLanguage = when (lang) {
        BandLanguage.EN -> BandLanguage.JA
        BandLanguage.JA -> BandLanguage.EN
    }

    /** Does this action set [variable]? */
    fun setsVariable(action: ActionSpec, variable: String): Boolean =
        action.type == VAR_SET && action.args["name"]?.trim() == variable

    fun definesVariable(task: Task, variable: String): Boolean =
        task.actions.any { setsVariable(it, variable) }

    /**
     * The task with [variable]'s value replaced, and nothing else touched.
     *
     * Every other action, label, argument and flag is carried through unchanged — the labels in this
     * task are 白い熊's bilingual documentation of each setting, and a switch of display language has
     * no business rewriting them.
     */
    fun retarget(task: Task, variable: String, value: String): Task =
        task.copy(
            actions = task.actions.map { action ->
                if (setsVariable(action, variable)) {
                    action.copy(args = action.args + ("value" to value))
                } else {
                    action
                }
            },
        )

    /**
     * Pick the task that defines [variable].
     *
     * Found by content rather than by name, so renaming the task does not break the pill — the
     * reference-by-name rule is about what crosses a bundle boundary, and nothing here does. If more
     * than one task defines it, [SETTINGS_TASK] breaks the tie; a genuinely ambiguous workspace is
     * reported rather than guessed at.
     */
    fun choose(candidates: List<Task>): Task? = when (candidates.size) {
        0 -> null
        1 -> candidates.single()
        else -> candidates.firstOrNull { it.name == SETTINGS_TASK }
    }

    /** What the pill did, in the language the *result* is displayed in. */
    sealed interface Outcome {
        /** The task was rewritten, saved and run; the window should now show [language]. */
        data class Switched(val language: BandLanguage) : Outcome
        data class Failed(val reason: Loc) : Outcome
    }

    /**
     * Rewrite the settings task to [to], save it, run it.
     *
     * The run is what makes the switch real, so its failure is the pill's failure: if the task
     * aborts, the value is already saved but nothing has published it, and reporting success would
     * leave the window in the old language with no explanation.
     */
    suspend fun switchTo(
        appContext: Context,
        db: AppDatabase,
        to: BandLanguage,
    ): Outcome = switchTo(appContext, db, to, VARIABLE) { ctx, tag -> BandSettings.setLanguage(ctx, tag) }

    /**
     * The same switch for any band's language variable.
     *
     * Both bands keep their display language in the one settings task and both read a preference at
     * window launch, so the procedure is identical and only the two names differ — the variable the
     * task defines, and the preference the window reads. Parameterising beats a second copy: the
     * reasoning above about WHY it goes the long way round is the valuable part, and a copy would
     * duplicate the code while leaving the reasoning behind.
     */
    suspend fun switchTo(
        appContext: Context,
        db: AppDatabase,
        to: BandLanguage,
        variable: String,
        persist: (Context, String) -> Unit,
    ): Outcome {
        val candidates = db.taskDao().getAll()
            .mapNotNull { entity -> runCatching { entity.toDomain() }.getOrNull() }
            .filter { definesVariable(it, variable) }

        val task = choose(candidates) ?: return Outcome.Failed(
            if (candidates.isEmpty()) {
                Loc(
                    "No task sets %$variable — expected $SETTINGS_TASK.",
                    "%$variable を設定するタスクがありません。$SETTINGS_TASK のはずです。",
                )
            } else {
                Loc(
                    "${candidates.size} tasks set %$variable — cannot tell which is the setting.",
                    "%$variable を設定するタスクが ${candidates.size} 個あります。どれが設定か判断できません。",
                )
            },
        )

        return try {
            db.taskDao().update(retarget(task, variable, to.tag).toEntity())
            val result = executeAndLogTask(
                appContext = appContext,
                db = db,
                task = db.taskDao().getById(task.id)?.toDomain() ?: retarget(task, variable, to.tag),
                source = "健康 language pill",
                logTag = "BandLanguageSwitch",
            )
            if (result.report.success) {
                // What `band.charts` would have done with `lang=%Band_Language` on the next open.
                // Written after the run, not before: if the task aborts, the window's language and
                // the task's value are both still the old one, which is the honest pair.
                persist(appContext, to.tag)
                Outcome.Switched(to)
            } else {
                val why = result.skippedReason ?: result.report.results
                    .firstNotNullOfOrNull { (it as? ActionResult.Failure)?.message }
                Outcome.Failed(
                    Loc(
                        "${task.name} did not finish${why?.let { " — $it" }.orEmpty()}",
                        "${task.name} が完了しませんでした${why?.let { "— $it" }.orEmpty()}",
                    ),
                )
            }
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            Outcome.Failed(Loc("Could not switch language — $why", "言語を切り替えられませんでした — $why"))
        }
    }
}
