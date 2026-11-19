package com.shopledger.pos.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** App-wide "can we use the database?" flag. MainActivity shows the recovery screen when Failed. */
object DatabaseHealth {
    sealed interface State {
        data object Ok : State
        data class Failed(val reason: String, val safetyCopy: File?) : State
    }

    private val _state = MutableStateFlow<State>(State.Ok)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The first failure wins; later ones are usually consequences of it. */
    fun markFailed(reason: String, safetyCopy: File?) {
        if (_state.value is State.Ok) _state.value = State.Failed(reason, safetyCopy)
    }
}
