package com.pidog.lufstracer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.pidog.lufstracer.ui.theme.LUFSTracerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LUFSTracerTheme {
                AudioDecoderScreen(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}