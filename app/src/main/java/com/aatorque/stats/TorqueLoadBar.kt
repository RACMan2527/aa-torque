package com.aatorque.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import kotlin.math.max

/**
 * Compact, fixed-thickness horizontal engine-load strip.
 * Uses Torque PID 0104 (calculated engine load), scaled from 0 to 100%.
 */
class TorqueLoadBar : Fragment() {
    private lateinit var gauge: EngineLoadBarView
    private lateinit var valueText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_load_bar, container, false)
        gauge = view.findViewById(R.id.engineLoadGauge)
        valueText = view.findViewById(R.id.engineLoadValue)
        return view
    }

    fun setupBar(data: TorqueData) {
        data.notifyUpdate = this::onUpdate
    }

    private fun onUpdate(data: TorqueData) {
        val value = data.lastData
        if (value.isFinite()) {
            val percent = value.toFloat().coerceIn(0f, 100f)
            gauge.setProgress(percent)
            valueText.text = "${percent.toInt()}%"
        }
    }
}

/** Draws a true horizontal progress strip; its thickness never changes with the value. */
class EngineLoadBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val cornerRadius = 3f * density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4B4B4B.toInt()
        style = Paint.Style.FILL
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        style = Paint.Style.FILL
    }
    private var progress = 0f

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 100f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        val track = RectF(0f, 0f, width, height)
        canvas.drawRoundRect(track, cornerRadius, cornerRadius, trackPaint)
        val filledWidth = width * progress / 100f
        if (filledWidth > 0f) {
            val fill = RectF(0f, 0f, max(filledWidth, cornerRadius), height)
            canvas.save()
            canvas.clipRect(0f, 0f, filledWidth, height)
            canvas.drawRoundRect(fill, cornerRadius, cornerRadius, progressPaint)
            canvas.restore()
        }
    }
}
