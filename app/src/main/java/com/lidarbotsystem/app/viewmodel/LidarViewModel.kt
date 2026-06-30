package com.lidarbotsystem.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lidarbotsystem.app.data.LidarContract
import com.lidarbotsystem.app.network.LidarUdpClient
import com.lidarbotsystem.app.network.MdnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.net.InetAddress

private const val MDNS_HOSTNAME = "lidarbase.local"

enum class ConnectionState { DISCONNECTED, RESOLVING, CONNECTED, ERROR }

/** One rendered scan point, already decoded from a scan_sample frame. */
data class ScanPoint(val angleCdeg: Int, val distanceMm: Int)

data class HealthSnapshot(val faultFlags: Int, val batteryMv: Int, val timestampMs: Long)

/**
 * Owns the UDP receive loop, mDNS resolution, and outbound control
 * commands. Exposes everything the UI needs as StateFlow so LidarScreen
 * stays a pure function of state (no business logic in composables).
 *
 * Incremental rendering design: [points] accumulates one entry per
 * scan_sample as it arrives and is cleared the moment a scan_complete
 * frame is seen, so the UI is always drawing "the sweep currently in
 * progress" rather than an ever-growing buffer -- this bounds both memory
 * and per-frame Canvas redraw cost to one sweep's worth of points
 * regardless of how long the app has been connected.
 */
class LidarViewModel(application: Application) : AndroidViewModel(application) {
    private val udpClient = LidarUdpClient()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _points = MutableStateFlow<List<ScanPoint>>(emptyList())
    val points: StateFlow<List<ScanPoint>> = _points.asStateFlow()

    private val _health = MutableStateFlow<HealthSnapshot?>(null)
    val health: StateFlow<HealthSnapshot?> = _health.asStateFlow()

    private val _sweepRange = MutableStateFlow(0 to 18000) // centidegrees, matches firmware default 0-180deg
    val sweepRange: StateFlow<Pair<Int, Int>> = _sweepRange.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var resolvedHost: InetAddress? = null
    private var receiveJob: Job? = null

    /** Resolves lidarbase.local (or uses [manualIp] if mDNS fails / isn't
     * provided) and starts the telemetry receive loop. Safe to call again
     * to retry or switch hosts -- cancels any previous receive loop first. */
    fun connect(manualIp: String? = null) {
        receiveJob?.cancel()
        _lastError.value = null
        _connectionState.value = ConnectionState.RESOLVING

        viewModelScope.launch {
            val host = if (!manualIp.isNullOrBlank()) {
                runCatching { InetAddress.getByName(manualIp) }.getOrNull()
            } else {
                MdnsResolver.resolveWithLock(getApplication(), MDNS_HOSTNAME)
            }

            if (host == null) {
                _connectionState.value = ConnectionState.ERROR
                _lastError.value = if (manualIp.isNullOrBlank()) {
                    "mDNS resolution of $MDNS_HOSTNAME failed -- enter base-radio's IP manually"
                } else {
                    "Could not resolve manual IP '$manualIp'"
                }
                return@launch
            }

            resolvedHost = host
            _connectionState.value = ConnectionState.CONNECTED
            startReceiveLoop()
        }
    }

    private fun startReceiveLoop() {
        receiveJob = viewModelScope.launch(Dispatchers.IO) {
            udpClient.receiveTelemetry()
                .onEach { frame -> handleFrame(frame) }
                .catch { e ->
                    _connectionState.value = ConnectionState.ERROR
                    _lastError.value = "UDP receive failed: ${e.message}"
                }
                .collect()
        }
    }

    private fun handleFrame(frame: LidarContract.Frame) {
        when (frame.type) {
            LidarContract.Type.SCAN_SAMPLE -> {
                val sample = LidarContract.decodeScanSample(frame)
                if (sample.distanceMm != LidarContract.OUT_OF_RANGE) {
                    _points.value = _points.value + ScanPoint(sample.angleCdeg, sample.distanceMm)
                }
            }
            LidarContract.Type.SCAN_COMPLETE -> {
                // Start the next sweep fresh -- see class doc.
                _points.value = emptyList()
            }
            LidarContract.Type.HEALTH_STATUS -> {
                val h = LidarContract.decodeHealthStatus(frame)
                _health.value = HealthSnapshot(h.faultFlags, h.batteryMv, h.timestampMs)
            }
            LidarContract.Type.CONTROL_ACK -> {
                // Surfaced for future use (e.g. a toast/snackbar); current
                // UI doesn't need to react beyond knowing the link is alive,
                // which connectionState already reflects.
            }
            else -> {
                // Unknown type: data contract violation upstream. Dropped,
                // not crashed on -- matches every other layer's handling of
                // malformed/unexpected frames.
            }
        }
    }

    fun startScan() = sendCommand(LidarContract.CmdId.START_SCAN)

    fun stopScan() = sendCommand(LidarContract.CmdId.STOP_SCAN)

    fun setSweepRange(minCdeg: Int, maxCdeg: Int) {
        _sweepRange.value = minCdeg to maxCdeg
        sendCommand(LidarContract.CmdId.SET_SWEEP_RANGE, param1 = minCdeg, param2 = maxCdeg)
    }

    fun ping() = sendCommand(LidarContract.CmdId.PING)

    private fun sendCommand(cmdId: Int, param1: Int = 0, param2: Int = 0) {
        val host = resolvedHost ?: run {
            _lastError.value = "Not connected -- resolve base-radio before sending commands"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val wire = LidarContract.encodeControlCommand(cmdId, param1, param2)
            runCatching { udpClient.sendControlCommand(host, wire) }
                .onFailure { e -> _lastError.value = "Send failed: ${e.message}" }
        }
    }

    override fun onCleared() {
        super.onCleared()
        receiveJob?.cancel()
    }
}
