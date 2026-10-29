package com.aatorque.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Configurable horizontal bar for a single Torque PID and user-defined range. */
class TorqueLoadBar : Fragment() {
    private lateinit var gauge: EngineLoadBarView
    private lateinit var titleText: TextView
    private lateinit var valueText: TextView
    private var minimum = 0.0
    private var maximum = 100.0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_load_bar, container, false)
        gauge = view.findViewById(R.id.engineLoadGauge)
        titleText = view.findViewById(R.id.loadBarTitle)
        valueText = view.findViewById(R.id.engineLoadValue)
        return view
    }

    fun setupBar(data: TorqueData, minValue: Double, maxValue: Double, title: String) {
        minimum = minValue
        maximum = if (maxValue > minValue) maxValue else minValue + 1.0
        titleText.text = title
        gauge.setRange(minimum.toFloat(), maximum.toFloat())
        gauge.setProgress(data.lastData.toFloat())
        valueText.text = formatReading(data.lastData)
        data.notifyUpdate = {
            val value = it.lastData
            gauge.setProgress(value.toFloat())
            valueText.text = formatReading(value)
        }
    }

    private fun formatReading(value: Double): String {
        return if (value.isFinite()) {
            if (value == value.roundToInt().toDouble()) {
                value.roundToInt().toString()
            } else {
                String.format(Locale.US, "%.1f", value)
            }
        } else "--"
    }
}

/** Fixed-thickness bar with six tick labels spanning the configured min/max range. */
class EngineLoadBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4B4B4B.toInt()
        style = Paint.Style.FILL
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBDBDBD.toInt()
        strokeWidth = max(2f, 2f * density)
        style = Paint.Style.STROKE
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        strokeWidth = max(1f, 1f * density)
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD5D5D5.toInt()
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
    }
    private val cornerRadius = 2f * density
    private var reading = 0f
    private var minimum = 0f
    private var maximum = 100f

    fun setRange(minValue: Float, maxValue: Float) {
        minimum = minValue
        maximum = if (maxValue > minValue) maxValue else minValue + 1f
        reading = reading.coerceIn(minimum, maximum)
        invalidate()
    }

    fun setProgress(value: Float) {
        reading = if (value.isFinite()) value.coerceIn(minimum, maximum) else minimum
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Keep the active range 3% clear of both horizontal edges so end ticks
        // and rounded corners remain visible on the dashboard.
        val horizontalInset = w * 0.03f
        val usableWidth = w - horizontalInset * 2f
        val track = RectF(horizontalInset, 0f, w - horizontalInset, h)
        canvas.drawRoundRect(track, cornerRadius, cornerRadius, trackPaint)

        val fraction = ((reading - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
        val filledWidth = usableWidth * fraction
        if (filledWidth > 0f) {
            val fill = RectF(horizontalInset, 0f, horizontalInset + filledWidth, h)
            canvas.save()
            canvas.clipRect(horizontalInset, 0f, horizontalInset + filledWidth, h)
            canvas.drawRoundRect(fill, cornerRadius, cornerRadius, progressPaint)
            canvas.restore()
        }

        // Draw ticks after the active fill so the tick marks sit visibly on top
        // of the red portion, as well as on the unfilled track.
        val tickTop = h * 0.12f
        val tickBottom = h * 0.76f
        val labelY = minOf(h - 2f * density, h * 0.94f)
        // Four short minor ticks divide each interval between the six primary ticks.
        val minorTickTop = h * 0.28f
        val minorTickBottom = h * 0.58f
        for (interval in 0 until 5) {
            for (minor in 1..4) {
                val fractionAtTick = (interval + minor / 5f) / 5f
                val x = horizontalInset + usableWidth * fractionAtTick
                canvas.drawLine(x, minorTickTop, x, minorTickBottom, minorTickPaint)
            }
        }

        for (index in 0..5) {
            val fractionAtTick = index / 5f
            val x = horizontalInset + usableWidth * fractionAtTick
            val tickValue = minimum + (maximum - minimum) * fractionAtTick
            canvas.drawLine(x, tickTop, x, tickBottom, tickPaint)
            canvas.drawText(formatTick(tickValue), x, labelY, labelPaint)
        }
    }

    private fun formatTick(value: Float): String {
        return if (value.isFinite() && value == value.roundToInt().toFloat()) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }
}
