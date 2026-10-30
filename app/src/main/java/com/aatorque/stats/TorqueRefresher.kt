package com.aatorque.stats
import android.os.Handler
import android.os.Looper
import com.aatorque.datastore.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import timber.log.Timber
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class ConnectStatus {
    CONNECTING_TORQUE, CONNECTING_ECU, CONNECTED, SETUP_GAUGE
}

class TorqueRefresher {
    val data = HashMap<Int, TorqueData>()
    private val executor = ScheduledThreadPoolExecutor(7)
    val handler = Handler(Looper.getMainLooper())
    private var conWatcher = MutableStateFlow(ConnectStatus.CONNECTING_TORQUE)
    val connectStatus = conWatcher.asSharedFlow()
    val cache = mutableMapOf<Int, HashMap<Int, TorqueData>>()
    private var scheduledRefreshInterval: Long? = null

    companion object {
        @Volatile
        var REFRESH_INTERVAL = 300L
    }

    fun populateQuery(pos: Int, screen: Int, query: Display): TorqueData {
        data[pos]?.stopRefreshing(true)
        val cacheItem = cache[screen]?.get(pos)
        val torqueData = if (cacheItem?.display?.equals(query) == true) {
            cacheItem
        } else {
            TorqueData(query)
        }
        cache.getOrPut(screen) {
            HashMap()
        }[pos] = torqueData
        data[pos] = torqueData
        Timber.i("Setting query: $query for pos $pos on screen $screen")
        return torqueData
    }

    suspend fun makeExecutors(service: TorqueService) {
        var foundValid = false
        val refreshInterval = REFRESH_INTERVAL.coerceAtLeast(1L)

        // A changed preference must also change already-created PID schedules.
        // Cancel the old timers so sampling and the bar animation use one interval.
        if (scheduledRefreshInterval != null && scheduledRefreshInterval != refreshInterval) {
            data.values.forEach { torqueData ->
                torqueData.stopRefreshing()
            }
        }
        scheduledRefreshInterval = refreshInterval

        data.values.forEachIndexed { index, torqueData ->
            val refreshOffset = (refreshInterval / data.size) * index
            if (torqueData.pid != null) {
                foundValid = true
                if (torqueData.refreshTimer == null) {
                    Timber.i("Scheduled item in position $index with $refreshOffset delay")
                    doRefresh(service, torqueData)
                    torqueData.refreshTimer = executor.scheduleWithFixedDelay({
                        try {
                            doRefresh(service, torqueData)
                        } catch (e: Exception) {
                            Timber.e("Refresh failed in pos $index", e)
                        }
                    }, refreshOffset, refreshInterval, TimeUnit.MILLISECONDS)
                }
            } else {
                Timber.i("No reason to schedule item in position $index")
            }
        }
        conWatcher.emit(if (foundValid) conWatcher.value else ConnectStatus.SETUP_GAUGE)
        service.addConnectCallback {
            if (conWatcher.value == ConnectStatus.CONNECTING_TORQUE) {
                runBlocking {
                    conWatcher.emit(ConnectStatus.CONNECTING_ECU)
                }
            }
        }
    }

    fun doRefresh(service: TorqueService, torqueData: TorqueData) {
        service.runIfConnected { ts ->
            val value = try {
                ts.getPIDValuesAsDouble(arrayOf(torqueData.pid!!))[0]
            } catch (e: ArrayIndexOutOfBoundsException) {
                Timber.e("Torque returned invalid data for ${torqueData.pid}")
                return@runIfConnected
            }
            torqueData.lastData = value
            torqueData.hasReceivedReading = true
            Timber.d("Got valid $value from torque for ${torqueData.display.label}")
            if (value != 0.0) {
                torqueData.hasReceivedNonZero = true
            }
            if (value != 0.0 || torqueData.hasReceivedNonZero || torqueData.allowInitialZeroUpdates) {
                handler.post {
                    torqueData.sendNotifyUpdate()
                }
            }
            // Preserve master's connection rule: CONNECTED only after a non-zero
            // reading has arrived, or after a previously non-zero reading.
            if (value != 0.0 || torqueData.hasReceivedNonZero) {
                runBlocking {
                    conWatcher.emit(ConnectStatus.CONNECTED)
                }
            }
        }
    }

    fun stopExecutors() {
        Timber.i("Telling Torque refreshers to stop")
        for (td in data.values) {
            td.stopRefreshing()
        }
    }

    fun hasChanged(idx: Int, otherScreen: Display?): Boolean {
        if (!data.containsKey(idx)) return true
        return data[idx]?.display?.equals(otherScreen) != true
    }

    fun updateIfNeeded(pos: Int, screen: Int, query: Display): TorqueData {
        return if (hasChanged(pos, query)) {
            populateQuery(pos, screen, query)
        } else {
            data[pos]!!
        }
    }

}
