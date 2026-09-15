package dev.logb.android.core.design

import androidx.annotation.DrawableRes
import dev.logb.android.R

/** The object-type glyphs, ported from the web app's `Icon.svelte`, so an object looks the same on both. */
object LogbIcons {
    @DrawableRes
    fun forType(type: String): Int = when (type) {
        "car" -> R.drawable.ic_type_car
        "e_bike" -> R.drawable.ic_type_e_bike
        "bike" -> R.drawable.ic_type_bike
        "motorcycle" -> R.drawable.ic_type_motorcycle
        "home" -> R.drawable.ic_type_home
        "appliance" -> R.drawable.ic_type_appliance
        "tool" -> R.drawable.ic_type_tool
        "body" -> R.drawable.ic_type_body
        else -> R.drawable.ic_type_object
    }

    /** An icon *name* (`CustomTypes.ICONS`, the web's IconName) to its drawable. */
    @DrawableRes
    fun forIcon(name: String): Int = when (name) {
        "document" -> R.drawable.ic_type_document
        "camera" -> R.drawable.ic_type_camera
        "car" -> R.drawable.ic_type_car
        "e-bike" -> R.drawable.ic_type_e_bike
        "bike" -> R.drawable.ic_type_bike
        "motorcycle" -> R.drawable.ic_type_motorcycle
        "home" -> R.drawable.ic_type_home
        "appliance" -> R.drawable.ic_type_appliance
        "tool" -> R.drawable.ic_type_tool
        "body" -> R.drawable.ic_type_body
        else -> R.drawable.ic_type_object
    }
}
