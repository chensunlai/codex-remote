package dev.codexremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.codexremote.app.ui.CodexRemoteApp
import dev.codexremote.app.ui.CodexRemoteTheme
import dev.codexremote.app.model.AppTheme

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()
    private val systemBarStyle by lazy {
        // AndroidX retains the first style for configuration changes; read the current app theme.
        SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) {
            mainViewModel.state.value.theme == AppTheme.DARK
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = systemBarStyle, navigationBarStyle = systemBarStyle)
        setContent {
            val state by mainViewModel.state.collectAsStateWithLifecycle()
            SideEffect {
                enableEdgeToEdge(statusBarStyle = systemBarStyle, navigationBarStyle = systemBarStyle)
            }
            CodexRemoteTheme(fontScale = state.fontScale, theme = state.theme) {
                Surface(Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.background) {
                    CodexRemoteApp(state = state, viewModel = mainViewModel)
                }
            }
        }
    }

}
