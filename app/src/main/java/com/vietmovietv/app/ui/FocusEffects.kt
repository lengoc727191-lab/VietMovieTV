package com.vietmovietv.app.ui

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

object FocusEffects {

    private val focusInInterpolator = OvershootInterpolator(1.15f)
    private val focusOutInterpolator = DecelerateInterpolator()

    fun attachPopEffect(view: View, scaleUp: Float = 1.08f, elevationPx: Float = 14f) {
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            if (hasFocus) {
                v.elevation = elevationPx
                v.translationZ = elevationPx * 0.6f
                v.animate()
                    .scaleX(scaleUp)
                    .scaleY(scaleUp)
                    .setDuration(170L)
                    .setInterpolator(focusInInterpolator)
                    .start()
            } else {
                v.elevation = 0f
                v.translationZ = 0f
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(130L)
                    .setInterpolator(focusOutInterpolator)
                    .start()
            }
        }
    }
}
