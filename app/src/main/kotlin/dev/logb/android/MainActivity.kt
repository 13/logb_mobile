package dev.logb.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import dev.logb.android.core.prefs.ThemeMode
import dev.logb.android.core.auth.Session
import dev.logb.android.core.design.theme.LogbTheme
import dev.logb.android.feature.onboarding.BootstrapScreen
import dev.logb.android.feature.onboarding.ServerScreen
import dev.logb.android.feature.onboarding.SignInScreen
import dev.logb.android.navigation.AppNavHost

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @javax.inject.Inject lateinit var shareInbox: dev.logb.android.feature.share.ShareInbox

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        shareInbox.offer(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) shareInbox.offer(intent)
        setContent {
            val root: RootViewModel = hiltViewModel()
            val appearance by root.appearance.collectAsStateWithLifecycle()
            LogbTheme(
                darkTheme = when (appearance.theme) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true },
                dynamicColor = appearance.dynamicColor,
            ) {
                Surface(Modifier.fillMaxSize()) {
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
                                false -> AppNavHost(shareInbox)
                            }
                        }
                    }
                }
            }
        }
    }
}
