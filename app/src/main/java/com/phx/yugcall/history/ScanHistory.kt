package com.phx.yugcall.history

import com.phx.yugcall.AnalysisOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where a scan's audio came from. OVERLAY is a live session started from the
 * floating call-protection bubble rather than the in-app Listen button. */
enum class ScanSource { LIVE, OVERLAY, FILE }

data class ScanRecord(
    val id: Long,
    val source: ScanSource,
    val timestampMillis: Long,
    val outcome: AnalysisOutcome.Scored,
)

/**
 * App-wide scan history, shared by the in-app screen and the call-overlay
 * service so a scan started from either surface shows up in "Recent Scans" —
 * there is one history, not one per surface.
 */
class ScanHistoryStore {
    private val _records = MutableStateFlow<List<ScanRecord>>(emptyList())
    val records: StateFlow<List<ScanRecord>> = _records.asStateFlow()

    private var nextId = 0L

    /** No-ops for outcomes that aren't a completed score (NoSpeech/Failed). */
    fun add(outcome: AnalysisOutcome, source: ScanSource) {
        val scored = outcome as? AnalysisOutcome.Scored ?: return
        val record = ScanRecord(
            id = nextId++,
            source = source,
            timestampMillis = System.currentTimeMillis(),
            outcome = scored,
        )
        _records.update { (listOf(record) + it).take(MAX_HISTORY) }
    }

    private companion object {
        const val MAX_HISTORY = 50
    }
}
