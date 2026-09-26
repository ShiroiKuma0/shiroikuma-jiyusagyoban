package com.opentasker.ui.utils

import com.opentasker.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpandCollapseSemanticsTest {
    @Test
    fun anOpenRowSaysItIsExpandedAndOffersToCollapse() {
        assertEquals(R.string.a11y_expanded to R.string.action_collapse, disclosureLabels(expanded = true))
    }

    @Test
    fun aClosedRowSaysItIsCollapsedAndOffersToExpand() {
        assertEquals(R.string.a11y_collapsed to R.string.action_expand, disclosureLabels(expanded = false))
    }
}
