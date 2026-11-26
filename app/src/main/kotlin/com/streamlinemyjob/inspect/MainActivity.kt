package com.streamlinemyjob.inspect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.streamlinemyjob.inspect.ui.CoordinatorNav
import com.streamlinemyjob.inspect.ui.CoordinatorTheme

/** The app's one activity (designs/stage-1-foundation.md 4.16). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as CoordinatorApp).container
        setContent {
            CoordinatorTheme { CoordinatorNav(container) }
        }
    }
}
