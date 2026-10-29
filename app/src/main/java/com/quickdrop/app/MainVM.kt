package com.quickdrop.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

class MainVM(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    val engine = TransferEngine(app, store, viewModelScope)

    var nickname by mutableStateOf(store.nickname)
        private set

    var visible by mutableStateOf(store.visible)
        private set

    fun saveNick(n: String) {
        store.nickname = n
        nickname = store.nickname
        engine.restart()
    }

    fun updateVisible(v: Boolean) {
        store.visible = v
        visible = v
        engine.restart()
    }

    override fun onCleared() {
        engine.shutdown()
    }
}
