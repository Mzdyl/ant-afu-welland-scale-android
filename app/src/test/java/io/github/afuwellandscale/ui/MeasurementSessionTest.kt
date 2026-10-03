package io.github.afuwellandscale.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementSessionTest {
    @Test fun launchStartsOnlyOneAttemptEvenAfterCancelOrTimeout() {
        val session = MeasurementSession()
        assertTrue(session.shouldStart())
        assertFalse(session.shouldStart())
        assertFalse(session.shouldStart())
    }

    @Test fun returningFromBackgroundStartsANewAttempt() {
        val session = MeasurementSession()
        assertTrue(session.shouldStart())
        session.leave(externalRequest = false, changingConfiguration = false)
        assertTrue(session.shouldStart())
        assertFalse(session.shouldStart())
    }

    @Test fun permissionAndBluetoothDialogsDoNotTriggerAnotherAttempt() {
        val session = MeasurementSession()
        assertTrue(session.shouldStart())
        session.leave(externalRequest = true, changingConfiguration = false)
        assertFalse(session.shouldStart())
    }

    @Test fun rotationDoesNotRestartACancelledOrCompletedAttempt() {
        val session = MeasurementSession()
        assertTrue(session.shouldStart())
        session.leave(externalRequest = false, changingConfiguration = true)
        assertFalse(MeasurementSession(session.attempted).shouldStart())
    }
}
