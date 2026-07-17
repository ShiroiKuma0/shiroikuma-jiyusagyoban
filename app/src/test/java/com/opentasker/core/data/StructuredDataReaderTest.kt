package com.opentasker.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StructuredDataReaderTest {
    private fun read(format: String, source: String, path: String) =
        StructuredDataReader.read(format, source, path)?.values

    // ---- JSON ----

    @Test
    fun jsonObjectPath() {
        assertEquals(listOf("Ann"), read("json", """{"user":{"name":"Ann"}}""", "user.name"))
    }

    @Test
    fun jsonArrayIndexPath() {
        assertEquals(listOf("2"), read("json", """{"items":[{"id":1},{"id":2}]}""", "items[1].id"))
    }

    @Test
    fun jsonArrayResultExpandsToAllValues() {
        assertEquals(listOf("a", "b", "c"), read("json", """{"tags":["a","b","c"]}""", "tags"))
    }

    @Test
    fun jsonRootArrayIndex() {
        assertEquals(listOf("10"), read("json", "[10,20,30]", "[0]"))
    }

    @Test
    fun jsonMissingPathAndMalformedFailClosed() {
        assertNull(read("json", """{"a":1}""", "b.c"))
        assertNull(read("json", "{not json", "a"))
    }

    // ---- CSV ----

    @Test
    fun csvColumnExtraction() {
        assertEquals(listOf("b", "d"), read("csv", "a,b\nc,d", "1"))
    }

    @Test
    fun csvCellExtraction() {
        assertEquals(listOf("d"), read("csv", "a,b\nc,d", "1,1"))
    }

    @Test
    fun csvAllCellsWhenNoPath() {
        assertEquals(listOf("a", "b", "c", "d"), read("csv", "a,b\nc,d", ""))
    }

    @Test
    fun csvOutOfRangeCellFailsClosedButMissingColumnIsEmpty() {
        assertNull(read("csv", "a,b", "5,0")) // no such row
        assertEquals(emptyList<String>(), read("csv", "a,b", "9")) // no such column -> no values
    }

    @Test
    fun csvQuotedFieldsKeepCommasAndDoubledQuotes() {
        assertEquals(
            listOf("Doe, John", "42"),
            read("csv", "\"Doe, John\",42", ""),
        )
        assertEquals(
            listOf("say \"hi\"", "b"),
            read("csv", "\"say \"\"hi\"\"\",b", ""),
        )
    }

    @Test
    fun csvQuotedFieldsKeepEmbeddedNewlines() {
        assertEquals(
            listOf("line1\nline2", "x"),
            read("csv", "\"line1\nline2\",x", ""),
        )
    }

    @Test
    fun csvColumnExtractionWithQuotedCommas() {
        // Without quote handling this returned " John" for column 1.
        assertEquals(listOf("42"), read("csv", "\"Doe, John\",42", "1"))
    }

    // ---- XML ----

    @Test
    fun xmlElementPathReturnsAllMatches() {
        val xml = "<root><item><name>A</name></item><item><name>B</name></item></root>"
        assertEquals(listOf("A", "B"), read("xml", xml, "root/item/name"))
    }

    @Test
    fun xmlMalformedFailsClosed() {
        assertNull(read("xml", "<root><item></root>", "root/item"))
    }

    @Test
    fun xmlRejectsDoctypeToPreventXxe() {
        val withDoctype =
            "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x \"y\">]><root><item>A</item></root>"
        assertNull(read("xml", withDoctype, "root/item"))
    }

    // ---- guards ----

    @Test
    fun unknownFormatAndOversizedInputFailClosed() {
        assertNull(read("yaml", "a: 1", "a"))
        assertNull(read("json", "\"" + "x".repeat(StructuredDataReader.MAX_INPUT_CHARS) + "\"", ""))
    }
}
