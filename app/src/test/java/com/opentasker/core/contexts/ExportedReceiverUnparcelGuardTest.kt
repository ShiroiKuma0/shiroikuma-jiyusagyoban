package com.opentasker.core.contexts

import com.opentasker.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every exported receiver has to survive a bundle it cannot read.
 *
 * Reading an extra unparcels it, and any app can send a value whose class does not exist in this
 * process. Below API 33 that throws on the first read; on 33+ it throws when the poisoned key is
 * the one read. Uncaught inside a receiver it kills the process hosting the automation engine,
 * which START_STICKY restarts for the sender to do again, so an unguarded read is a remote
 * denial of service against every automation the user has.
 *
 * A source gate because the behaviour needs a real Parcel and a class this process lacks, which is
 * an instrumented concern; what is checkable here is that no exported receiver reads an extra
 * outside a guard.
 */
class ExportedReceiverUnparcelGuardTest {

    @Test
    fun theExportedPushReceiverGuardsItsExtras() {
        val receiver = ProductionSources.read("com/opentasker/core/contexts/PushEventReceiver.kt")
        val parser = ProductionSources.read("com/opentasker/core/contexts/PushContextEvents.kt")

        assertTrue(
            "PushEventReceiver.onReceive must not let a parse failure escape",
            "runCatching" in receiver,
        )
        assertTrue(
            "reading a push extra must be guarded; extras itself can throw",
            "runCatching { extras?.get(name) }" in parser,
        )
        assertTrue(
            "the bytes extra is read the same way and needs the same guard",
            "runCatching { extras?.get(NTFY_EXTRA_MESSAGE_BYTES) }" in parser,
        )
    }

    @Test
    fun theExportedLocaleFireReceiverGuardsItsBundle() {
        val onReceive = ProductionSources.block(
            "com/opentasker/core/plugins/locale/LocalePluginTarget.kt",
            "class LocaleSettingFireReceiver",
            "if (!LocaleGrantStore(context).isValid(grant, taskId))",
        )

        assertTrue(
            "the Locale fire receiver must guard getBundleExtra",
            "runCatching {" in onReceive && "getBundleExtra" in onReceive,
        )
        assertTrue(
            "parsing the task id out of that bundle must be guarded too",
            "runCatching { LocalePluginTarget.parseTaskId(bundle) }" in onReceive,
        )
    }
}
