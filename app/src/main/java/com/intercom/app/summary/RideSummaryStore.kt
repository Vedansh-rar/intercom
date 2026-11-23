package com.intercom.app.summary

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where a finished ride waits for the summary screen.
 *
 * It has to outlive the call controller, which dies with the service the moment the call
 * ends. A process-wide holder in memory is enough: the summary is shown once and dropped, it
 * is never written to disk, and a fresh call clears it.
 */
object RideSummaryStore {
    private val _latest = MutableStateFlow<RideSummary?>(null)
    val latest: StateFlow<RideSummary?> = _latest

    fun publish(summary: RideSummary) { _latest.value = summary }
    fun clear() { _latest.value = null }
}
