package com.opentasker.ui.charts.huawei

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the board considers "busy", which is what decides whether its tiles can be pressed.
 *
 * A small property to test, and it was wrong in the way small properties are: it asked whether a
 * task had been handed over and forgot that a sync is also something in flight. Underneath the sync
 * dialog every tile stayed live, the sync tile included, so its own sync could be started a second
 * time while the first was still going (白い熊, 2026-08-29). The band refused the second — the
 * runner holds a mutex — but the refusal is not the point: the button should not have been there to
 * press.
 */
class HuaweiBoardStateTest {

    @Test
    fun `an idle board is not busy`() {
        assertFalse(HuaweiBoardState().anyBusy)
    }

    @Test
    fun `a handed-over task makes the board busy`() {
        assertTrue(HuaweiBoardState(busy = "同期（Huawei） -- [727]").anyBusy)
    }

    @Test
    fun `a sync in flight makes the board busy, with no task handed over`() {
        val state = HuaweiBoardState(sync = BoardSync(phase = "接続しています", running = true))
        assertTrue(
            "a running sync is in flight whether or not a task is also open",
            state.anyBusy,
        )
    }

    /**
     * And stops being busy the moment it finishes, while the dialog is still up.
     *
     * The dialog outlives the run on purpose — it is where the result is read — so a finished sync
     * must release the board rather than leaving every tile disabled behind a dialog showing a
     * summary.
     */
    @Test
    fun `a finished sync still on screen does not hold the board`() {
        val state = HuaweiBoardState(sync = BoardSync(summary = "1137 samples", running = false))
        assertFalse(state.anyBusy)
    }

    /**
     * Switching holds the board; merely having the dialog open does not.
     *
     * Opening it touches no radio — the band cannot be asked its language, so there is nothing to
     * wait for and the other tiles stay usable. Only the push takes the band's single connection.
     */
    @Test
    fun `switching holds the board, and an open dialog alone does not`() {
        assertTrue(
            HuaweiBoardState(
                language = BoardLanguage(remembered = "en-US", switching = true),
            ).anyBusy,
        )
        assertFalse(HuaweiBoardState(language = BoardLanguage(remembered = "en-US")).anyBusy)
    }
}
