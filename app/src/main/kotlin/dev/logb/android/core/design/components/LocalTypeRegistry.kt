package dev.logb.android.core.design.components

import androidx.compose.runtime.staticCompositionLocalOf
import dev.logb.android.core.domain.TypeRegistry

/** The signed-in account's types, provided once at the navigation root. */
val LocalTypeRegistry = staticCompositionLocalOf { TypeRegistry.EMPTY }
