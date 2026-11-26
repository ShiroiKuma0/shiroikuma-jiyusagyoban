package com.sysadmindoc.ruledeck.core.text

/**
 * "1 variable" or "2 variables", for English text built in code: step failures, import
 * warnings, run log notes. Text from resources uses `<plurals>` instead.
 */
fun countOf(count: Long, one: String, other: String = "${one}s"): String =
    "$count ${if (count == 1L) one else other}"

fun countOf(count: Int, one: String, other: String = "${one}s"): String = countOf(count.toLong(), one, other)

/** "1 scene was" or "2 scenes were", for a count that leads a sentence about what happened to them. */
fun countWas(count: Int, one: String, other: String = "${one}s"): String =
    "${countOf(count, one, other)} ${if (count == 1) "was" else "were"}"
