package com.outpostzero.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.outpostzero.terminal.ui.OutpostZeroApp
import com.outpostzero.terminal.ui.OutpostZeroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OutpostZeroTheme {
                OutpostZeroApp()
            }
        }
    }
}
