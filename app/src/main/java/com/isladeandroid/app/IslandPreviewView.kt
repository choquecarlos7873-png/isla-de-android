package com.isladeandroid.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Illustrative device; the live service alone uses the actual camera bounds. */
class IslandPreviewView(context: Context) : View(context) {
    var profile: IslandProfile = IslandProfile()
        set(value) { field = value; invalidate() }
    var portrait = true
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val screenWidth = if (portrait) 360 else 780
        val screenHeight = if (portrait) 780 else 360
        val visibleHeight = if (portrait) 240 else screenHeight
        val scale = minOf((width - 12f) / screenWidth, (height - 12f) / visibleHeight)
        if (scale <= 0) return
        canvas.save()
        canvas.translate((width - screenWidth * scale) / 2f, 6f)
        canvas.scale(scale, scale)
        paint.color = Color.rgb(209, 226, 216)
        canvas.drawRoundRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), 32f, 32f, paint)
        paint.color = Color.rgb(236, 244, 239)
        canvas.drawRoundRect(5f, 5f, screenWidth - 5f, screenHeight - 5f, 28f, 28f, paint)
        val rect = IslandGeometry.place(screenWidth, screenHeight, 1f, if (portrait) 0 else 1, profile)
        paint.color = Color.BLACK
        val radius = minOf(rect.width, rect.height) / 2f
        canvas.drawRoundRect(rect.x.toFloat(), rect.y.toFloat(), (rect.x + rect.width).toFloat(),
            (rect.y + rect.height).toFloat(), radius, radius, paint)
        canvas.restore()
    }
}
