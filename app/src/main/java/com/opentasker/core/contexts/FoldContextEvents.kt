package com.opentasker.core.contexts

import com.opentasker.core.engine.variables.PersistentGlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fold-posture events ("fold"), fired when the foldable settles into a new posture
 * (folded / semi / unfolded). The current posture is also exposed as the super-global %FOLD so the
 * triggered task can read it. An EVENT context may narrow with config fold=folded,unfolded (CSV) — or
 * omit it to fire on any fold change. Detection lives in FoldDetector (a DisplayManager.DisplayListener),
 * the sibling of OrientationContextEvents / OrientationDetector.
 */
object FoldContextEvents {
    private val folds = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ContextEvent> = folds.asSharedFlow()

    /** Update %FOLD without emitting an event — used to seed the current posture when the detector starts,
     *  so merely starting it (e.g. on service boot) doesn't spuriously trigger a fold profile. */
    fun setCurrent(fold: String) {
        PersistentGlobalScope.set(0L, "FOLD", fold)
    }

    fun publish(fold: String) {
        setCurrent(fold)
        folds.tryEmit(
            ContextEvent(
                type = "event",
                matched = true,
                metadata = mapOf("event" to "fold", "fold" to fold),
            ),
        )
    }
}
