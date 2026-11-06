package com.streamcam.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Latest-value holder that separates transient updates from committed persistence.
 *
 * [update] stores a new value and immediately forwards it downstream (e.g. to the camera engine)
 * but never writes to storage. [commit] persists the latest value. Rapid control movements (a
 * slider drag) therefore produce one in-memory/camera update per sample while producing a single
 * storage write when the user finishes adjusting — instead of one DataStore transaction per sample.
 */
class TransientSettings<T>(
    initial: T,
    private val onApply: (T) -> Unit = {},
    private val onPersist: (T) -> Unit = {},
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<T> = _state.asStateFlow()

    /** The latest value (same object the public [state] flow emits). */
    val value: T get() = _state.value

    /** Updates the value and pushes it downstream; does **not** persist. */
    fun update(newValue: T) {
        _state.value = newValue
        onApply(newValue)
    }

    /** Persists the latest value — call when the user finishes adjusting a control. */
    fun commit() {
        onPersist(_state.value)
    }
}
