package com.opentasker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.opentasker.ui.theme.OpenTaskerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenTaskerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    Home(modifier = Modifier.padding(inner))
                }
            }
        }
    }
}

@Composable
private fun Home(modifier: Modifier = Modifier) {
    Text(
        text = "OpenTasker v0.1.0\n\nProfiles, Tasks, Scenes, Variables — coming online.",
        modifier = modifier
    )
}
