package com.sysadmindoc.ruledeck.ui.screens

import android.content.Context
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import com.sysadmindoc.ruledeck.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** A-341: a count of one reads "1 context", not "1 context(s)", and every count in a message gets its own plural. */
class CountPluralsInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext.let { base ->
        base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.US) })
    }

    private fun plural(id: Int, count: Int) = context.resources.getQuantityString(id, count, count)

    @Test
    fun oneOfAThingReadsSingularAndMoreReadPlural() {
        assertEquals("1 context", plural(R.plurals.label_context_count, 1))
        assertEquals("2 contexts", plural(R.plurals.label_context_count, 2))
        assertEquals("1 action", plural(R.plurals.label_action_count, 1))
        assertEquals("1 other task running", plural(R.plurals.service_notification_multiple_tasks, 1))
        assertEquals("Cleared 1 run log entry", plural(R.plurals.run_log_cleared, 1))
        assertEquals("Cleared 3 run log entries", plural(R.plurals.run_log_cleared, 3))
        assertEquals("1 global variable", plural(R.plurals.header_variables_detail, 1))
    }

    @Test
    fun aMessageWithSeveralCountsGivesEachItsOwnPlural() {
        val message = uiMessage(
            R.string.ui_message_bundle_exported,
            uiPluralMessage(R.plurals.count_tasks, 1, 1),
            uiPluralMessage(R.plurals.count_profiles, 2, 2),
            uiPluralMessage(R.plurals.count_scenes, 1, 1),
        )

        assertEquals("Exported 1 task, 2 profiles, 1 scene", message.resolve(context))
    }

    @Test
    fun aCountThatIsntFirstStillPicksThePlural() {
        val body = context.resources.getQuantityString(R.plurals.delete_task_references_body, 1, "Lights", 1)

        assertEquals("\"Lights\" is referenced by 1 other automation. Choose what happens to that reference before it is deleted.", body)
    }
}
