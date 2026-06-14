package com.opentasker.core.engine

import com.opentasker.core.model.ActionSpec

/** Flow-control action type ids, handled directly by the [TaskRunner] rather than the registry. */
object FlowControl {
    const val IF = "flow.if"
    const val ELSE = "flow.else"
    const val ENDIF = "flow.endif"
    const val FOREACH = "flow.foreach"
    const val ENDFOR = "flow.endfor"
    const val STOP = "flow.stop"

    val ALL = setOf(IF, ELSE, ENDIF, FOREACH, ENDFOR, STOP)

    fun isControl(type: String): Boolean = type in ALL
}

/**
 * Resolved jump targets for block-structured flow control within a single task's action list.
 *
 * All maps are keyed by the action index of the opening/closing marker so the interpreter can jump
 * in O(1). [analyze] validates that every `flow.if`/`flow.foreach` is correctly paired and nested;
 * an unbalanced list produces an [error] string and the task fails honestly instead of misbehaving.
 */
data class FlowStructure(
    /** flow.if index -> matching flow.else index (or null when there is no else). */
    val ifToElse: Map<Int, Int>,
    /** flow.if index -> matching flow.endif index. */
    val ifToEndif: Map<Int, Int>,
    /** flow.else index -> matching flow.endif index. */
    val elseToEndif: Map<Int, Int>,
    /** flow.foreach index -> matching flow.endfor index. */
    val foreachToEndfor: Map<Int, Int>,
    /** flow.endfor index -> matching flow.foreach index. */
    val endforToForeach: Map<Int, Int>,
    val error: String? = null,
) {
    companion object {
        fun analyze(actions: List<ActionSpec>): FlowStructure {
            val ifToElse = mutableMapOf<Int, Int>()
            val ifToEndif = mutableMapOf<Int, Int>()
            val elseToEndif = mutableMapOf<Int, Int>()
            val foreachToEndfor = mutableMapOf<Int, Int>()
            val endforToForeach = mutableMapOf<Int, Int>()

            // Stack entries: marker type + opening index (+ optional else index for if-blocks).
            data class Frame(val type: String, val openIndex: Int, var elseIndex: Int? = null)
            val stack = ArrayDeque<Frame>()

            actions.forEachIndexed { index, spec ->
                when (spec.type) {
                    FlowControl.IF -> stack.addLast(Frame(FlowControl.IF, index))
                    FlowControl.FOREACH -> stack.addLast(Frame(FlowControl.FOREACH, index))
                    FlowControl.ELSE -> {
                        val frame = stack.lastOrNull()
                            ?: return error("flow.else without matching flow.if at step ${index + 1}")
                        if (frame.type != FlowControl.IF) {
                            return error("flow.else inside a ${frame.type} block at step ${index + 1}")
                        }
                        if (frame.elseIndex != null) {
                            return error("duplicate flow.else at step ${index + 1}")
                        }
                        frame.elseIndex = index
                    }
                    FlowControl.ENDIF -> {
                        val frame = stack.removeLastOrNull()
                            ?: return error("flow.endif without matching flow.if at step ${index + 1}")
                        if (frame.type != FlowControl.IF) {
                            return error("flow.endif closing a ${frame.type} block at step ${index + 1}")
                        }
                        frame.elseIndex?.let { elseIndex ->
                            ifToElse[frame.openIndex] = elseIndex
                            elseToEndif[elseIndex] = index
                        }
                        ifToEndif[frame.openIndex] = index
                    }
                    FlowControl.ENDFOR -> {
                        val frame = stack.removeLastOrNull()
                            ?: return error("flow.endfor without matching flow.foreach at step ${index + 1}")
                        if (frame.type != FlowControl.FOREACH) {
                            return error("flow.endfor closing a ${frame.type} block at step ${index + 1}")
                        }
                        foreachToEndfor[frame.openIndex] = index
                        endforToForeach[index] = frame.openIndex
                    }
                }
            }

            if (stack.isNotEmpty()) {
                val unclosed = stack.last()
                val marker = if (unclosed.type == FlowControl.IF) "flow.if" else "flow.foreach"
                return error("unclosed $marker block opened at step ${unclosed.openIndex + 1}")
            }

            return FlowStructure(ifToElse, ifToEndif, elseToEndif, foreachToEndfor, endforToForeach)
        }

        private fun error(message: String) = FlowStructure(
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), error = message,
        )
    }
}
