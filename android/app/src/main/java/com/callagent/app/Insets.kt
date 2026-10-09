package com.callagent.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Android 15 draws apps edge to edge: keep content clear of the navigation bar and cutouts. */
fun padForSystemBars(view: View) {
    val left = view.paddingLeft
    val right = view.paddingRight
    val bottom = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(left + bars.left, v.paddingTop, right + bars.right, bottom + bars.bottom)
        insets
    }
}
