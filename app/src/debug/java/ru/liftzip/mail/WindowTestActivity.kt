package ru.liftzip.mail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*

/** Debug-only host: system navigation overlays may recreate the Activity at any time. */
class WindowTestActivity : ComponentActivity() {
    companion object {
        var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        var current: WindowTestActivity?=null
            private set
    }
    override fun onResume() {
        super.onResume()
        current=this
    }
    override fun onPause() {
        if(current===this)current=null
        super.onPause()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeMailWindow()
        setContent { content?.invoke() }
    }
}
