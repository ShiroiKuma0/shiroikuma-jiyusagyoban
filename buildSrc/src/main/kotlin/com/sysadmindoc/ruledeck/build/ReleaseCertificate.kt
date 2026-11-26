package com.sysadmindoc.ruledeck.build

/**
 * The certificate README.md publishes under the package name, as lowercase hex without colons.
 *
 * Lives in buildSrc rather than app/build.gradle.kts: a lambda that calls a script-level function
 * captures the script object, and the configuration cache can't store it, which failed the
 * cache-enabled local release gate.
 */
fun publishedCertificateSha256(readme: String): String {
    val published = Regex("""com\.sysadmindoc\.ruledeck\r?\n((?:[0-9A-F]{2}:){31}[0-9A-F]{2})""").find(readme)
        ?: error("README.md must publish the release certificate on the line after com.sysadmindoc.ruledeck")
    return published.groupValues[1].replace(":", "").lowercase()
}
