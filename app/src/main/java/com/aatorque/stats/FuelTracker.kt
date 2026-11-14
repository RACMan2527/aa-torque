package com.aatorque.stats

import android.content.Context
import android.content.ContentValues
import android.content.ContentUris
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.prowl.torque.remote.ITorqueService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Independent tank accounting. Reads standard OBD PIDs using Torque's API; it never
 * modifies the gauge polling schedule. No simulated or invented tank statistics.
 *
 * 012F fuel level (%), 010D vehicle speed (km/h), 015E engine fuel rate (L/h).
 * If fuel rate is unavailable, distance/time remain valid but MPG and gallons
 * are deliberately unavailable. No assumption about the vehicle's tank capacity.
 */
class FuelTracker(context: Context, private val service: TorqueService) {
    data class Summary(val id: Long, val miles: Double, val gallons: Double?,
                       val seconds: Long, val mpg: Double?)
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, "Fuel-Tracker.json"))
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "V-Sport-Fuel-Tracker").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null
    private var state = load().apply {
        // Pending summaries from an earlier app session must not appear on startup.
        remove("pending")
    }
    private var lastSampleMs = 0L
    private var candidate: Double? = null
    private var candidateCount = 0
    private var runningLevel: Double? = null
    private var runningSeen = false
    private var zeroRpmCount = 0
    private var stoppedLevel: Double? = null
    private var restartValidation = false
    private var restartSamples = 0
    @Volatile private var latest: Summary? = null

    private fun load(): JSONObject = try {
        val bytes = file.openRead().use { it.readBytes() }
        JSONObject(String(bytes, Charsets.UTF_8))
    } catch (_: Exception) {
        JSONObject().put("schema", 1)
            .put("miles", 0.0).put("gallons", 0.0).put("seconds", 0L)
            .put("fuelRateValid", true).put("fuelRateSeen", false).put("completed", JSONArray())
    }

    private fun save() {
        val bytes = state.toString(2).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        // The private atomic copy is authoritative; the Downloads copy is a
        // readable companion to the diagnostic logs, never the only copy.
        try { mirrorToDownloads(bytes) }
        catch (e: Exception) {
            DiagnosticLog.event("FUEL_TRACKER_EXPORT_ERROR", e.javaClass.simpleName)
        }
    }

    private var downloadsUri: Uri? = null
    private fun mirrorToDownloads(bytes: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "V-Sport Gauges")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "Fuel-Tracker.json").writeBytes(bytes)
            return
        }
        val resolver = app.contentResolver
        if (downloadsUri == null) {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf("Fuel-Tracker.json", "Download/V-Sport Gauges/"), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    downloadsUri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                }
            }
        }
        if (downloadsUri == null) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "Fuel-Tracker.json")
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/V-Sport Gauges/")
            }
            downloadsUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }
        val uri = downloadsUri ?: throw IllegalStateException("Cannot create fuel tracker export")
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("Cannot write fuel tracker export")
    }

    @Synchronized fun start() {
        if (task != null) return
        task = executor.scheduleWithFixedDelay({
            try { sample() }
            catch (e: Exception) {
                DiagnosticLog.event("FUEL_TRACKER_ERROR", e.javaClass.simpleName + " " + e.message)
            }
        }, 0L, 2L, TimeUnit.SECONDS)
    }

    @Synchronized fun stop() {
        task?.cancel(false)
        task = null
        lastSampleMs = 0L
        candidate = null
        candidateCount = 0
        zeroRpmCount = 0
        restartValidation = false
        restartSamples = 0
        runningSeen = false
        runningLevel = null
        stoppedLevel = null
    }

    @Synchronized fun close() {
        stop()
        executor.shutdown()
    }

    @Synchronized fun pendingSummary(): Summary? = latest ?: run {
        val obj = state.optJSONObject("pending") ?: return@run null
        summaryFromJson(obj).also { latest = it }
    }

    @Synchronized fun acknowledge(id: Long) {
        if (state.optJSONObject("pending")?.optLong("id") == id) {
            state.remove("pending")
            save()
            latest = null
            DiagnosticLog.event("FUEL_TANK_ACKNOWLEDGED", "id=$id")
        }
    }

    private fun summaryFromJson(o: JSONObject): Summary {
        val gallons = if (o.isNull("gallons") || !o.has("gallons")) null else o.getDouble("gallons")
        return Summary(o.getLong("id"), o.getDouble("miles"), gallons,
            o.getLong("seconds"), gallons?.takeIf { it > 0.0 }?.let { o.getDouble("miles") / it })
    }

    private fun read(ts: ITorqueService, pid: String): Double? = try {
        val v = ts.getPIDValuesAsDouble(arrayOf(pid)).firstOrNull()
        v?.takeIf { it.isFinite() && it >= 0.0 }
    } catch (_: Exception) { null }

    @Synchronized private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val ts = service.torqueService ?: run { lastSampleMs = 0L; return }
        if (!ts.isConnectedToECU) { lastSampleMs = 0L; return }
        val level = read(ts, "2f,0")?.takeIf { it <= 100.0 }
            ?: run { lastSampleMs = 0L; return }
        val speed = read(ts, "0d,0")?.takeIf { it <= 250.0 }
        val rate = read(ts, "5e,0")?.takeIf { it <= 400.0 }
        val rpm = read(ts, "0c,0")?.takeIf { it <= 8000.0 }
        val delta = if (lastSampleMs == 0L) 0.0
            else ((now - lastSampleMs) / 1000.0).coerceIn(0.0, 4.0)
        lastSampleMs = now

        // Only count connected, continuous readings; no extrapolation over gaps.
        if (delta > 0 && speed != null) {
            state.put("miles", state.optDouble("miles") + speed * delta / 3600.0 * 0.621371192)
            if (speed > 0.5) state.put("seconds", state.optLong("seconds") + delta.toLong())
            if (rate != null) {
                if (rate > 0.0) state.put("fuelRateSeen", true)
                state.put("gallons", state.optDouble("gallons") + rate * delta / 3600.0 / 3.785411784)
            } else state.put("fuelRateValid", false)
        }

        // A stationary running engine never qualifies as a refuel.
        // Only three confirmed zero-RPM samples after running, followed by
        // an actual running restart, can arm the short validation window.
        if (rpm != null && rpm > 300.0) {
            if (stoppedLevel != null && !restartValidation) {
                restartValidation = true
                restartSamples = 0
                candidate = null
                candidateCount = 0
                DiagnosticLog.event("FUEL_REFILL_RESTART", "preStop=$stoppedLevel")
            }
            zeroRpmCount = 0
            runningSeen = true
            if (!restartValidation) runningLevel = level
            if (restartValidation) {
                // Cancel on movement: later stoplights cannot validate a refill.
                if (speed == null || speed >= 1.0) {
                    DiagnosticLog.event("FUEL_REFILL_REJECTED", "reason=restart_moving")
                    restartValidation = false
                    stoppedLevel = null
                    candidate = null
                    candidateCount = 0
                    runningLevel = level
                } else {
                    restartSamples++
                    val baseline = stoppedLevel!!
                    if (level >= baseline + 12.0) {
                        if (candidate == null || abs(level - candidate!!) > 3.0) {
                            candidate = level
                            candidateCount = 1
                        } else candidateCount++
                        if (candidateCount >= 5) {
                            DiagnosticLog.event("FUEL_REFILL_ACCEPTED", "before=$baseline after=$level")
                            onRefill(level, baseline)
                            restartValidation = false
                            stoppedLevel = null
                            candidate = null
                            candidateCount = 0
                            runningLevel = level
                        }
                    } else {
                        candidate = null
                        candidateCount = 0
                    }
                    if (restartSamples >= 15 && restartValidation) {
                        DiagnosticLog.event("FUEL_REFILL_REJECTED", "reason=restart_window_expired")
                        restartValidation = false
                        stoppedLevel = null
                        candidate = null
                        candidateCount = 0
                        runningLevel = level
                    }
                }
            }
        } else if (rpm != null && rpm == 0.0 && runningSeen && !restartValidation) {
            zeroRpmCount++
            if (zeroRpmCount >= 3 && stoppedLevel == null && runningLevel != null) {
                stoppedLevel = runningLevel
                DiagnosticLog.event("FUEL_REFILL_ENGINE_STOP", "level=$stoppedLevel")
            }
        } else if (rpm != null && rpm > 0.0) {
            zeroRpmCount = 0
        }
        // Avoid a write on every 2s poll; save approximately every 30 seconds.
        if (now - lastPersistMs > 30_000L) {
            save()
            lastPersistMs = now
        }
    }

    private var lastPersistMs = 0L

    private fun onRefill(after: Double, before: Double) {
        // A refill without any tracked travel is not a completed driving period.
        if (state.optDouble("miles") < 1.0) {
            state.put("lowLevel", after)
            save()
            return
        }
        val id = System.currentTimeMillis()
        val gallons = if (state.optBoolean("fuelRateValid", true) && state.optBoolean("fuelRateSeen", false))
            state.optDouble("gallons") else null
        val finished = JSONObject().put("id", id)
            .put("miles", state.optDouble("miles"))
            .put("gallons", gallons ?: JSONObject.NULL)
            .put("seconds", state.optLong("seconds"))
            .put("beforeLevel", before).put("afterLevel", after)
        state.getJSONArray("completed").put(finished)
        state.put("pending", finished)
        latest = summaryFromJson(finished)
        DiagnosticLog.event("FUEL_TANK_COMPLETED",
            "id=$id miles=${latest!!.miles} gallons=${latest!!.gallons} seconds=${latest!!.seconds}")
        state.put("lowLevel", after)
        state.put("miles", 0.0)
        state.put("gallons", 0.0)
        state.put("seconds", 0L)
        state.put("fuelRateValid", true)
        state.put("fuelRateSeen", false)
        save()
    }
}
