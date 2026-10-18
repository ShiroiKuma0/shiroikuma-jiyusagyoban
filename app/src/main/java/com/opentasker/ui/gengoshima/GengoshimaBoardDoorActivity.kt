package com.opentasker.ui.gengoshima

import android.app.Activity
import android.os.Bundle

/**
 * The 言語島 board's door for other apps: `shiroikuma.jiyusagyoban.action.OPEN_GENGOSHIMA_BOARD`.
 *
 * 白い熊 kxkb fires it when its walk-capture review is left, so Back lands on the board again — the
 * board finished itself when its 「録音確認」 tile handed the task over, so it has to be opened anew
 * (docs/sister-app-contract-kxkb-gengoshima.md §8).
 *
 * A trampoline rather than an exported board: it reads NOTHING from the intent, so it can open exactly
 * one screen and cannot be talked into opening any other part of the app. No data crosses it.
 */
class GengoshimaBoardDoorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GengoshimaBoardActivity.open(this)
        finish()
    }
}
