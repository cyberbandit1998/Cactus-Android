package app.cactusai.assistant.history

import app.cactusai.core.history.HistoryEntry
import app.cactusai.core.history.HistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable view of the local command history. It never leaves the phone. */
class HistoryRepository(private val store: HistoryStore) {
    private val _entries = MutableStateFlow(store.all())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun add(entry: HistoryEntry) {
        // List keys must be unique, so a clash (two commands finishing in the same millisecond) is nudged apart.
        val id = maxOf(entry.id, (_entries.value.maxOfOrNull { it.id } ?: 0L) + 1)
        _entries.value = store.add(entry.copy(id = id))
    }

    fun clear() {
        store.clear()
        _entries.value = emptyList()
    }
}
