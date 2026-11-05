package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.di.DefaultAppContainer
import com.example.navigation.NexusAiAppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as? NexusAiApplication
        val container = app?.container ?: DefaultAppContainer(applicationContext)

        setContent {
            NexusAiAppRoot(appContainer = container)
        }
    }
}
