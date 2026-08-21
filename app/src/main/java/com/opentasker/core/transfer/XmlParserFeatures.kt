package com.opentasker.core.transfer

import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory

/**
 * Best-effort XXE / DOCTYPE hardening for Android XML factories.
 *
 * Android's Harmony/Expat factories throw [org.xml.sax.SAXNotRecognizedException] for the Apache
 * feature URIs. Making those throws fatal is what broke every on-device Tasker import (issue #5).
 * Text-level sanitizers remain the enforcement; these features are belt and braces on Xerces.
 */
internal fun SAXParserFactory.applyImportHardening() {
    setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
    setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
    setFeatureSafely("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
}

internal fun DocumentBuilderFactory.applyImportHardening() {
    setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
    setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
    setFeatureSafely("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
}

private fun SAXParserFactory.setFeatureSafely(name: String, value: Boolean) {
    runCatching { setFeature(name, value) }
}

private fun DocumentBuilderFactory.setFeatureSafely(name: String, value: Boolean) {
    runCatching { setFeature(name, value) }
}
