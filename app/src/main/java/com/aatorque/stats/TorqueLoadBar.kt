package com.aatorque.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.github.anastr.speedviewlib.ProgressiveGauge
import java.util.Locale

/**
 * Full-width engine-load strip styled to match the dark metallic VW Clubsport dial.
 * The data source is the standard Torque PID 0104 (calculated engine load).
 */
class TorqueLoadBar : Fragment() {
    private lateinit var gauge: ProgressiveGauge
    private lateinit var title: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_load_bar, container, false)
        gauge = view.findViewById(R.id.engineLoadGauge)
        title = view.findViewById(R.id.loadBarTitle)
        gauge.minSpeed = 0f
        gauge.maxSpeed = 100f
        gauge.unit = "%"
        gauge.withTremble = false
        gauge.speedTextListener = { speed -> "%.0f".format(Locale.getDefault(), speed) }
        return view
    }

    fun setupBar(data: TorqueData) {
        data.notifyUpdate = this::onUpdate
    }

    private fun onUpdate(data: TorqueData) {
        val value = data.lastData
        if (value.isFinite()) {
            gauge.speedTo(
                value.toFloat().coerceIn(0f, 100f),
                TorqueRefresher.REFRESH_INTERVAL
            )
        }
    }
}
