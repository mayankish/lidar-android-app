package com.lidarbotsystem.app.network

import com.lidarbotsystem.app.data.LidarContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * UDP transport for the data contract: receives telemetry/ack frames
 * broadcast by base-radio on :5005 and sends control_command frames
 * unicast to base-radio:5006.
 *
 * Two independent sockets are used deliberately: the receive socket is
 * bound to a fixed local port (5005) so it can receive base-radio's
 * broadcast datagrams, while the send socket is ephemeral-port (any free
 * local port) since base-radio's control listener doesn't care what port
 * a command arrives from -- mirroring how esp32-raw-mac-radio/base-radio's
 * telemetry_broadcast_task and control_listener_task are themselves two
 * independent sockets server-side.
 */
class LidarUdpClient {
    companion object {
        const val TELEMETRY_PORT = 5005
        const val CONTROL_PORT = 5006
    }

    /** Emits one [LidarContract.Frame] per valid datagram received on
     * :5005. Frames that fail to parse as exactly [LidarContract.WIRE_LEN]
     * bytes with a matching CRC are silently dropped at this layer --
     * counting/logging drops is base-radio's job (it already does this via
     * track_sequence()); the app's job is to only ever see valid frames. */
    fun receiveTelemetry(): Flow<LidarContract.Frame> = callbackFlow {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(TELEMETRY_PORT))
            soTimeout = 1000
        }
        val job = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(256)
            while (true) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    if (packet.length == LidarContract.WIRE_LEN) {
                        val wire = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                        val frame = LidarContract.unpack(wire)
                        if (frame != null) {
                            trySend(frame)
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // expected idle-poll timeout, used only so this loop can
                    // notice cancellation promptly; not an error
                } catch (e: Exception) {
                    if (socket.isClosed) break
                }
            }
        }
        awaitClose {
            job.cancel()
            socket.close()
        }
    }

    /** Sends a single control_command wire frame to [host]:5006. Opens and
     * closes a fresh ephemeral socket per call -- control commands are rare
     * (user-driven button presses), so there's no latency or resource
     * pressure that would justify keeping a send socket open persistently. */
    suspend fun sendControlCommand(host: InetAddress, wireFrame: ByteArray) {
        withContext(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                val packet = DatagramPacket(wireFrame, wireFrame.size, host, CONTROL_PORT)
                socket.send(packet)
            }
        }
    }
}
