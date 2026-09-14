package dev.logb.android.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.logb.android.R

@Composable
fun BottomBar(active: Destination?, onSelect: (Destination) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = active == Destination.Objects,
            onClick = { onSelect(Destination.Objects) },
            icon = { Icon(painterResource(R.drawable.ic_type_object), contentDescription = null) },
            label = { Text(stringResource(R.string.nav_objects)) },
        )
        NavigationBarItem(
            selected = active == Destination.Search,
            onClick = { onSelect(Destination.Search) },
            icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_search)) },
        )
        NavigationBarItem(
            selected = active == Destination.Settings,
            onClick = { onSelect(Destination.Settings) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_settings)) },
        )
    }
}
