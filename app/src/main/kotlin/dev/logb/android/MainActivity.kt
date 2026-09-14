package dev.logb.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.logb.android.core.auth.Session
import dev.logb.android.core.design.theme.LogbTheme
import dev.logb.android.feature.onboarding.ServerScreen
import dev.logb.android.feature.onboarding.SignInScreen

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
                        is Session.SignedIn -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            // Placeholder until the navigation shell lands.
                            Text(stringResource(R.string.signed_in_as, s.user.username))
                            Button(onClick = root::signOut) { Text(stringResource(R.string.sign_out)) }
                        }
                    }
                }
            }
        }
    }
}
