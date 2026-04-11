package com.airi.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.airi.assistant.ui.AiriApp

/**
 * MainActivity - Entry point for AIRI application
 * 
 * Uses Jetpack Compose for modern UI
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display
        enableEdgeToEdge()
        
        // Set Compose content
        setContent {
            AiriApp()
        }
    }
}
