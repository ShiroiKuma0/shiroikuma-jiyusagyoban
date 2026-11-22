package com.opentasker.core.text

/**
 * "1 variable" or "2 variables", for English text built in code: step failures, import
 * warnings, run log notes. Text from resources uses `<plurals>` instead.
 */
fun countOf(count: Long, one: String, other: String = "${one}s"): String =
    "$count ${if (count == 1L) one else other}"

fun countOf(count: Int, one: String, other: String = "${one}s"): String = countOf(count.toLong(), one, other)
