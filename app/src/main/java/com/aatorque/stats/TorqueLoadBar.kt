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
import kotlin.math.max

/**
 * Full-width horizontal calculated crank-horsepower strip.
 * Torque PID 62 is actual engine torque as a percentage of reference torque,
 * PID 63 is reference torque in Nm, and PID 0C is engine RPM.
 */
class TorqueLoadBar : Fragment() {
    private lateinit var gauge: EngineLoadBarView
    private lateinit var valueText: TextView
    private var actualTorquePercent = 0.0
    private var referenceTorqueNm = 0.0
    private var engineRpm = 0.0

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

    fun setupBar(actualTorque: TorqueData, referenceTorque: TorqueData, rpm: TorqueData) {
        actualTorque.notifyUpdate = {
            actualTorquePercent = it.lastData
            updatePower()
        }
        referenceTorque.notifyUpdate = {
            referenceTorqueNm = it.lastData
            updatePower()
        }
        rpm.notifyUpdate = {
            engineRpm = it.lastData
            updatePower()
        }
        updatePower()
    }

    private fun updatePower() {
        // Actual Engine Torque (PID 62) is a percentage, not lb-ft.
        // Convert reference torque from Nm to lb-ft, then calculate US horsepower.
        val hp = if (actualTorquePercent.isFinite() &&
            referenceTorqueNm.isFinite() && engineRpm.isFinite() &&
            actualTorquePercent >= 0.0 && referenceTorqueNm > 0.0 && engineRpm >= 0.0
        ) {
            ((actualTorquePercent / 100.0) * referenceTorqueNm * 0.737562 * engineRpm / 5252.0)
        } else 0.0
        val safeHp = hp.coerceIn(0.0, 500.0)
        gauge.setProgress(safeHp.toFloat())
        valueText.text = "${safeHp.toInt()} HP"
    }
}

/** Fixed-thickness horizontal horsepower strip with 100 HP tick marks. */
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
        strokeWidth = max(1f, density)
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD5D5D5.toInt()
        textSize = 8f * density
        textAlign = Paint.Align.CENTER
    }
    private val cornerRadius = 2f * density
    private var horsepower = 0f

    fun setProgress(value: Float) {
        horsepower = value.coerceIn(0f, 500f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val horizontalInset = 1f * density
        val usableWidth = w - horizontalInset * 2f
        val trackHeight = 4f * density
        val trackTop = 1f * density
        val trackBottom = trackTop + trackHeight
        val track = RectF(horizontalInset, trackTop, w - horizontalInset, trackBottom)
        canvas.drawRoundRect(track, cornerRadius, cornerRadius, trackPaint)

        val filledWidth = usableWidth * horsepower / 500f
        if (filledWidth > 0f) {
            val fill = RectF(horizontalInset, trackTop, horizontalInset + filledWidth, trackBottom)
            canvas.save()
            canvas.clipRect(horizontalInset, trackTop, horizontalInset + filledWidth, trackBottom)
            canvas.drawRoundRect(fill, cornerRadius, cornerRadius, progressPaint)
            canvas.restore()
        }

        val tickTop = trackBottom + 1f * density
        val tickBottom = tickTop + 4f * density
        val labelY = minOf(h - 1f * density, tickBottom + labelPaint.textSize)
        for (hp in 0..500 step 100) {
            val x = horizontalInset + usableWidth * hp / 500f
            canvas.drawLine(x, tickTop, x, tickBottom, tickPaint)
            canvas.drawText(hp.toString(), x, labelY, labelPaint)
        }
    }
}
