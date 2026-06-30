package com.lidarbotsystem.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.lidarbotsystem.app.viewmodel.ConnectionState
import com.lidarbotsystem.app.viewmodel.LidarViewModel
import com.lidarbotsystem.app.viewmodel.ScanPoint
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Top-level screen: connection controls, sweep/scan controls, and the
 * polar scan-point Canvas. Pure presentation -- all decisions (what's
 * connected, what points exist) come from [LidarViewModel]'s StateFlows.
 */
@Composable
fun LidarScreen(viewModel: LidarViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val points by viewModel.points.collectAsState()
    val health by viewModel.health.collectAsState()
    val sweepRange by viewModel.sweepRange.collectAsState()
    val lastError by viewModel.lastError.collectAsState()

    var manualIp by remember { mutableStateOf("") }
    var minDeg by remember { mutableStateOf((sweepRange.first / 100).toString()) }
    var maxDeg by remember { mutableStateOf((sweepRange.second / 100).toString()) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("lidar-bot-system") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            ConnectionRow(
                connectionState = connectionState,
                manualIp = manualIp,
                onManualIpChange = { manualIp = it },
                onConnect = { viewModel.connect(manualIp.ifBlank { null }) },
            )

            if (lastError != null) {
                Text(
                    text = lastError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            health?.let { h ->
                Text(
                    "Battery: ${h.batteryMv} mV   Faults: 0x${h.faultFlags.toString(16)}",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            ScanCanvas(
                points = points,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(vertical = 12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { viewModel.startScan() }) { Text("Start") }
                Button(onClick = { viewModel.stopScan() }) { Text("Stop") }
                Button(onClick = { viewModel.ping() }) { Text("Ping") }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = minDeg,
                    onValueChange = { minDeg = it },
                    label = { Text("Min deg") },
                    modifier = Modifier.fillMaxWidth(0.3f),
                )
                OutlinedTextField(
                    value = maxDeg,
                    onValueChange = { maxDeg = it },
                    label = { Text("Max deg") },
                    modifier = Modifier.fillMaxWidth(0.4f),
                )
                Button(onClick = {
                    val minC = (minDeg.toIntOrNull() ?: 0) * 100
                    val maxC = (maxDeg.toIntOrNull() ?: 180) * 100
                    viewModel.setSweepRange(minC, maxC)
                }) { Text("Set") }
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    connectionState: ConnectionState,
    manualIp: String,
    onManualIpChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(connectionState)
        Text(connectionState.name)
        OutlinedTextField(
            value = manualIp,
            onValueChange = onManualIpChange,
            label = { Text("Manual IP (optional)") },
            modifier = Modifier.fillMaxWidth(0.5f),
        )
        Button(onClick = onConnect) { Text("Connect") }
    }
}

@Composable
private fun StatusDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF2E7D32)
        ConnectionState.RESOLVING -> Color(0xFFF9A825)
        ConnectionState.ERROR -> Color(0xFFC62828)
        ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
    }
    Box(modifier = Modifier.padding(end = 2.dp)) {
        Canvas(modifier = Modifier.aspectRatio(1f).fillMaxWidth(0.06f)) {
            drawCircle(color = color, radius = size.minDimension / 2)
        }
    }
}

/**
 * Renders [points] (angle_cdeg/distance_mm pairs, already filtered for
 * OUT_OF_RANGE upstream in the ViewModel) as a polar plot: angle measured
 * from the sensor's zero position, distance scaled to fit the canvas with
 * a fixed max-range ring set so the picture doesn't rescale jarringly
 * frame-to-frame as new points stream in mid-sweep.
 */
@Composable
private fun ScanCanvas(points: List<ScanPoint>, modifier: Modifier = Modifier) {
    val maxRangeMm = 2000f // VL53L0X practical max range; rings drawn at fixed fractions of this

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f * 0.9f

        // Range rings at 25/50/75/100% of maxRangeMm.
        for (frac in listOf(0.25f, 0.5f, 0.75f, 1.0f)) {
            drawCircle(
                color = Color(0xFFBDBDBD),
                radius = radius * frac,
                center = center,
                style = Stroke(width = 1f),
            )
        }

        for (point in points) {
            val angleRad = Math.toRadians(point.angleCdeg / 100.0)
            val r = (point.distanceMm / maxRangeMm).coerceIn(0f, 1f) * radius
            val x = center.x + (r * cos(angleRad)).toFloat()
            val y = center.y - (r * sin(angleRad)).toFloat() // screen Y is inverted vs. math convention
            drawCircle(color = Color(0xFF1565C0), radius = 3f, center = Offset(x, y))
        }
    }
}
