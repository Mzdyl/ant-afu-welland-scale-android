package io.github.afuwellandscale.ui

/** One automatic attempt per visit; permission dialogs and rotation are still the same visit. */
class MeasurementSession(var attempted: Boolean = false) {
    fun shouldStart(): Boolean {
        if (attempted) return false
        attempted = true
        return true
    }

    fun leave(externalRequest: Boolean, changingConfiguration: Boolean) {
        if (!externalRequest && !changingConfiguration) attempted = false
    }
}
