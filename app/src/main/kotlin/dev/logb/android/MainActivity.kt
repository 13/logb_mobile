package dev.logb.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.logb.android.core.design.components.DueBadge
import dev.logb.android.core.design.components.ObjectTypeIcon
import dev.logb.android.core.design.components.StatFigure
import dev.logb.android.core.design.theme.LogbTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LogbTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    // Placeholder until the navigation shell lands: proves the theme and icons render.
                    Column(Modifier.padding(16.dp)) {
                        Text("LogB")
                        ObjectTypeIcon("car")
                        StatFigure(label = "Spent", value = "€698.50")
                        DueBadge(count = 2)
                    }
                }
            }
        }
    }
}
