package dev.codexremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.codexremote.app.ui.CodexRemoteApp
import dev.codexremote.app.ui.CodexRemoteTheme

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by mainViewModel.state.collectAsStateWithLifecycle()
            CodexRemoteTheme(fontScale = state.fontScale) {
                CodexRemoteApp(state = state, viewModel = mainViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mainViewModel.setAppForeground(true)
    }

    override fun onStop() {
        mainViewModel.setAppForeground(false)
        super.onStop()
    }
}
