package com.sysadmindoc.ruledeck.core.actions

import com.opentasker.core.actions.ContactPickerCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping between a contacts step waiting for the picker and the activity that answers.
 * A mixed-up id hands one step another step's contact; a waiter that is never completed or
 * cancelled leaves its step hanging until it times out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactPickerCoordinatorTest {
    @Test
    fun everyRequestGetsItsOwnId() {
        val first = ContactPickerCoordinator.nextRequestId()
        val second = ContactPickerCoordinator.nextRequestId()

        assertNotEquals(first, second)
        assertTrue(first, first.startsWith("contacts-"))
    }

    @Test
    fun anAnswerReachesOnlyTheRequestItNames() {
        val mine = ContactPickerCoordinator.nextRequestId()
        val other = ContactPickerCoordinator.nextRequestId()
        val myWaiter = ContactPickerCoordinator.register(mine)
        val otherWaiter = ContactPickerCoordinator.register(other)

        // No contact chosen: the picker answers with no Uri, which still ends the wait.
        ContactPickerCoordinator.complete(mine, null)

        assertTrue(myWaiter.isCompleted)
        assertNull(myWaiter.getCompleted())
        assertFalse("another step's request must keep waiting", otherWaiter.isCompleted)
        ContactPickerCoordinator.remove(other)
    }

    @Test
    fun anAnswerForAnUnknownOrFinishedRequestChangesNothing() {
        val id = ContactPickerCoordinator.nextRequestId()
        val waiter = ContactPickerCoordinator.register(id)

        ContactPickerCoordinator.complete("contacts-never-issued", null)
        assertFalse(waiter.isCompleted)

        ContactPickerCoordinator.complete(id, null)
        ContactPickerCoordinator.complete(id, null)
        assertTrue("a second answer for the same id is ignored, not an error", waiter.isCompleted)
    }

    @Test
    fun aRemovedRequestIsCancelledAndALateAnswerIsDropped() {
        val id = ContactPickerCoordinator.nextRequestId()
        val waiter = ContactPickerCoordinator.register(id)

        ContactPickerCoordinator.remove(id)
        ContactPickerCoordinator.complete(id, null)

        assertTrue("the step that gave up must not stay suspended", waiter.isCancelled)
    }
}
