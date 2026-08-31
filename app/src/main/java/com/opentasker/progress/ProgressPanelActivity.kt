package com.opentasker.progress

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.opentasker.core.progress.ProgressPanel

/**
 * The progress panel as an ordinary Activity — the plan, the live run and the report.
 *
 * It used to be a `TYPE_APPLICATION_OVERLAY` window, which bought "floats over any app" and cost every
 * normal navigation gesture: Home did not put it away, it never appeared in the recents list, and there
 * was nothing to switch back to during a run that can last an hour (白い熊, 2026-07-28). As an Activity
 * all of that is the platform's job — Home backgrounds it, recents lists it, tapping it there resumes
 * exactly where it was.
 *
 * **The run does not live here.** It is driven by the task and the sister app's broadcasts, and this
 * only renders [ProgressPanel]'s state flow. So being backgrounded, or destroyed and recreated on a
 * rotation, changes nothing about the export — the state outlives the window, and a fresh instance
 * picks the panel up mid-run.
 *
 * `singleTask` (see the manifest) so returning to it resumes rather than stacking a second copy, and
 * Back backgrounds rather than finishes: pressing it during a run should get out of the way, not throw
 * the panel away. The panel goes for good only when the state does — 「キャンセル」, 「OK」, or a task
 * calling `progress.hide`.
 */
class ProgressPanelActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProgressPanelManager.attach(this)
        // Back is "get out of the way", not "abandon the run".
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        setContent {
            val state by ProgressPanel.state.collectAsState()
            // State cleared while we are on screen (OK / キャンセル / progress.hide) = nothing left to
            // show; close rather than sit on an empty window.
            if (state == null) finish() else ProgressPanelUi(state!!)
        }
    }

    override fun onDestroy() {
        ProgressPanelManager.detach(this)
        super.onDestroy()
    }
}
