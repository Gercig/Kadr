package ru.kadr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.kadr.app.data.ApiClient
import ru.kadr.app.ui.KadrApp
import ru.kadr.app.ui.theme.KadrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.init(this)
        enableEdgeToEdge()
        setContent {
            KadrTheme {
                KadrApp()
            }
        }
    }
}
