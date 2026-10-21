package com.opentasker.core.huawei

import com.opentasker.core.huawei.HuaweiBondWatch.ChangeKind
import com.opentasker.core.huawei.HuaweiBondWatch.State
import org.junit.Assert.assertEquals
import org.junit.Test

class HuaweiBondWatchTest {

    // BluetoothDevice constants, spelled out: the JVM stub would not inline them.
    private val none = 10
    private val bonding = 11
    private val bonded = 12

    @Test fun bondedToNoneIsALoss() =
        assertEquals(State.LOST to ChangeKind.LOST, HuaweiBondWatch.step(State.BONDED, watched = true, bond = none))

    @Test fun lostToBondedIsBack() =
        assertEquals(State.BONDED to ChangeKind.BACK, HuaweiBondWatch.step(State.LOST, watched = true, bond = bonded))

    @Test fun aLossIsReportedOnce() =
        assertEquals(State.LOST to null, HuaweiBondWatch.step(State.LOST, watched = true, bond = none))

    @Test fun adapterOffChangesNothing() =
        assertEquals(State.BONDED to null, HuaweiBondWatch.step(State.BONDED, watched = true, bond = null))

    @Test fun bondingIsNotAVerdict() =
        assertEquals(State.BONDED to null, HuaweiBondWatch.step(State.BONDED, watched = true, bond = bonding))

    /** 時計解除 clears the bind before the bond: a deliberate unpair is never a loss. */
    @Test fun noBindMeansUnwatched() =
        assertEquals(State.UNWATCHED to null, HuaweiBondWatch.step(State.BONDED, watched = false, bond = none))

    @Test fun firstBondedSightingStartsTheWatch() =
        assertEquals(State.BONDED to ChangeKind.WATCHING, HuaweiBondWatch.step(State.UNWATCHED, watched = true, bond = bonded))

    @Test fun stayingBondedSaysNothing() =
        assertEquals(State.BONDED to null, HuaweiBondWatch.step(State.BONDED, watched = true, bond = bonded))

    /** Never seen bonded (fresh install, or already gone): not a loss of ours to report. */
    @Test fun unwatchedToNoneStaysQuiet() =
        assertEquals(State.UNWATCHED to null, HuaweiBondWatch.step(State.UNWATCHED, watched = true, bond = none))
}
