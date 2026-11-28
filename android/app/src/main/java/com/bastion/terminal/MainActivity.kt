package com.bastion.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bastion.terminal.ui.BastionApp
import com.bastion.terminal.ui.BastionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.bastion.terminal.ui.ScreenTurn.apply(this)
        setContent {
            BastionTheme {
                BastionApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Kiosk mode, when BASTION is the device manager and the owner switched it on.
        com.bastion.terminal.ui.Kiosk.sync(this)
    }
}
