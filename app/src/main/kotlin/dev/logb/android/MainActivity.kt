package dev.logb.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.logb.android.feature.lock.LockScreen
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
            // The gate follows the process, not this activity: a picker or the camera is another activity of
            // the same process and must not count as leaving the app.
            DisposableEffect(root) {
                val observer = object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) { root.onForeground() }
                    override fun onStop(owner: LifecycleOwner) = root.onBackground()
                }
                ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
                onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
            }
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
                            // Only the very first bootstrap ever shows this; a later re-bootstrap
                            // (an import, a healed placeholder, ...) runs behind the normal UI.
                            val bootstrapNeeded by root.showFirstRunBootstrap.collectAsStateWithLifecycle()
                            val locked by root.locked.collectAsStateWithLifecycle()
                            when {
                                locked != false -> if (locked == true) LockScreen(onUnlock = root::unlock) else Box(Modifier.fillMaxSize())
                                bootstrapNeeded == null -> Box(Modifier.fillMaxSize())
                                bootstrapNeeded == true -> BootstrapScreen()
                                else -> AppNavHost(shareInbox)
                            }
                        }
                    }
                }
            }
        }
    }
}
