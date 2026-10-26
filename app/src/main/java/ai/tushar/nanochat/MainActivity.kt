package ai.tushar.nanochat

import ai.tushar.nanochat.ui.theme.NanoChatTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { NanoChatTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        graph.model.refresh()
    }
}
