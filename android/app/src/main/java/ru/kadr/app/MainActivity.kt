package ru.kadr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.kadr.app.data.local.LocalData
import ru.kadr.app.ui.KadrApp
import ru.kadr.app.ui.theme.KadrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalData.init(this)
        enableEdgeToEdge()
        setContent {
            KadrTheme {
                KadrApp()
            }
        }
    }
}
