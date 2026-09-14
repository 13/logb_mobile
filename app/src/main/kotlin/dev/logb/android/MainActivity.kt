package dev.logb.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.logb.android.core.auth.Session
import dev.logb.android.core.design.theme.LogbTheme
import dev.logb.android.feature.onboarding.BootstrapScreen
import dev.logb.android.feature.onboarding.ServerScreen
import dev.logb.android.feature.onboarding.SignInScreen
import dev.logb.android.navigation.AppNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LogbTheme {
                Surface(Modifier.fillMaxSize()) {
                    val root: RootViewModel = hiltViewModel()
                    val session by root.session.collectAsStateWithLifecycle()
                    when (val s = session) {
                        Session.Loading -> Box(Modifier.fillMaxSize())
                        Session.NeedsServer -> ServerScreen()
                        is Session.SignedOut -> SignInScreen()
                        is Session.SignedIn -> {
                            val bootstrapNeeded by root.bootstrapNeeded.collectAsStateWithLifecycle()
                            when (bootstrapNeeded) {
                                null -> Box(Modifier.fillMaxSize())
                                true -> BootstrapScreen()
                                false -> AppNavHost(onSignOut = root::signOut)
                            }
                        }
                    }
                }
            }
        }
    }
}
