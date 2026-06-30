package com.lidarbotsystem.app.data

/**
 * Canonical Kotlin implementation of the lidar-bot-system wire data
 * contract. Must stay byte-for-byte identical in behavior to:
 *   - stm32-lidar-firmware/Inc/data_contract.h (+ .c)
 *   - esp32-raw-mac-radio/common/data_contract.h (+ .c)
 *   - lidar-slam-dashboard/app/contract.py
 *
 * See DATA_CONTRACT.md at the repo root for the prose spec, including
 * the documented correction to the original spec: a frame is 16 bytes
 * on the wire (a 14-byte sof+type+seq+payload struct, plus a 2-byte
 * big-endian CRC16 trailer) -- "14 bytes" in the original spec refers only to
 * the pre-CRC struct.
 *
 * CRC16 spec: CRC-16/CCITT-FALSE (poly=0x1021, init=0xFFFF, refin=false,
 * refout=false, xorout=0x0000), transmitted big-endian -- deliberately
 * the opposite byte order of the little-endian `seq` field. Mismatched
 * CRC parameters or trailer byte order is the single most likely silent
 * bug in this whole system; if every packet fails to parse, check this
 * object against the other three language implementations first.
 */
object LidarContract {
    const val FRAME_LEN = 14
    const val WIRE_LEN = 16
    const val PAYLOAD_LEN = 10

    const val SOF_TELEMETRY = 0xAA
    const val SOF_CONTROL = 0xAB

    object Type {
        const val SCAN_SAMPLE = 0x01
        const val SCAN_COMPLETE = 0x02
        const val HEALTH_STATUS = 0x03
        const val CONTROL_COMMAND = 0x10
        const val CONTROL_ACK = 0x11
    }

    object CmdId {
        const val START_SCAN = 0x01
        const val STOP_SCAN = 0x02
        const val SET_SWEEP_RANGE = 0x03
        const val PING = 0x04
    }

    const val OUT_OF_RANGE = 0xFFFF

    /** Bit-by-bit CRC-16/CCITT-FALSE -- intentionally simple/auditable
     * over raw throughput, matching the same tradeoff documented in the
     * C implementations; packet rates here are low single-digit kHz at
     * most, never a bottleneck on a modern phone CPU. */
    fun crc16(data: ByteArray, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    data class Frame(
        val sof: Int,
        val type: Int,
        val seq: Int,
        val payload: ByteArray,
    )

    fun pack(sof: Int, type: Int, seq: Int, payload: ByteArray): ByteArray {
        require(payload.size == PAYLOAD_LEN) { "payload must be $PAYLOAD_LEN bytes" }
        val out = ByteArray(WIRE_LEN)
        out[0] = sof.toByte()
        out[1] = type.toByte()
        out[2] = (seq and 0xFF).toByte()
        out[3] = ((seq shr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 4, PAYLOAD_LEN)
        val crc = crc16(out, FRAME_LEN)
        out[14] = ((crc shr 8) and 0xFF).toByte()
        out[15] = (crc and 0xFF).toByte()
        return out
    }

    /** Returns null if the buffer isn't exactly WIRE_LEN bytes or the CRC
     * doesn't match -- callers must not act on a null result. */
    fun unpack(wire: ByteArray): Frame? {
        if (wire.size != WIRE_LEN) return null
        val expected = crc16(wire, FRAME_LEN)
        val received = ((wire[14].toInt() and 0xFF) shl 8) or (wire[15].toInt() and 0xFF)
        if (expected != received) return null

        val seq = (wire[2].toInt() and 0xFF) or ((wire[3].toInt() and 0xFF) shl 8)
        val payload = wire.copyOfRange(4, 4 + PAYLOAD_LEN)
        return Frame(
            sof = wire[0].toInt() and 0xFF,
            type = wire[1].toInt() and 0xFF,
            seq = seq,
            payload = payload,
        )
    }

    private fun getU16le(p: ByteArray, off: Int): Int =
        (p[off].toInt() and 0xFF) or ((p[off + 1].toInt() and 0xFF) shl 8)

    private fun putU16le(p: ByteArray, off: Int, v: Int) {
        p[off] = (v and 0xFF).toByte()
        p[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun getU32le(p: ByteArray, off: Int): Long =
        (p[off].toLong() and 0xFF) or
            ((p[off + 1].toLong() and 0xFF) shl 8) or
            ((p[off + 2].toLong() and 0xFF) shl 16) or
            ((p[off + 3].toLong() and 0xFF) shl 24)

    private fun putU32le(p: ByteArray, off: Int, v: Long) {
        p[off] = (v and 0xFF).toByte()
        p[off + 1] = ((v shr 8) and 0xFF).toByte()
        p[off + 2] = ((v shr 16) and 0xFF).toByte()
        p[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    data class ScanSample(val angleCdeg: Int, val distanceMm: Int, val timestampMs: Long)

    fun decodeScanSample(f: Frame): ScanSample {
        val angle = getU16le(f.payload, 0)
        val dist = getU16le(f.payload, 2)
        val ts = getU32le(f.payload, 4)
        return ScanSample(angle, dist, ts)
    }

    data class ScanComplete(val sweepDir: Int, val timestampMs: Long)

    fun decodeScanComplete(f: Frame): ScanComplete {
        val dir = f.payload[0].toInt() and 0xFF
        val ts = getU32le(f.payload, 2)
        return ScanComplete(dir, ts)
    }

    data class HealthStatus(val faultFlags: Int, val batteryMv: Int, val timestampMs: Long)

    fun decodeHealthStatus(f: Frame): HealthStatus {
        val flags = getU16le(f.payload, 0)
        val batt = getU16le(f.payload, 2)
        val ts = getU32le(f.payload, 4)
        return HealthStatus(flags, batt, ts)
    }

    data class ControlAck(val cmdId: Int, val status: Int, val timestampMs: Long)

    fun decodeControlAck(f: Frame): ControlAck {
        val cmdId = f.payload[0].toInt() and 0xFF
        val status = f.payload[1].toInt() and 0xFF
        val ts = getU32le(f.payload, 6)
        return ControlAck(cmdId, status, ts)
    }

    /** Builds a complete 16-byte control_command wire frame ready to send
     * via UDP unicast to base-radio:5006. `seq` is left at 0 -- the app
     * does not maintain its own outbound sequence space; neither
     * base-radio nor bot-radio reject frames on `seq` (it's informational
     * for loss-tracking only, see esp32-raw-mac-radio/base-radio's
     * track_sequence()), so a constant 0 is harmless. */
    fun encodeControlCommand(
        cmdId: Int,
        param1: Int = 0,
        param2: Int = 0,
        timestampMs: Long = System.currentTimeMillis(),
    ): ByteArray {
        val payload = ByteArray(PAYLOAD_LEN)
        payload[0] = cmdId.toByte()
        payload[1] = 0
        putU16le(payload, 2, param1)
        putU16le(payload, 4, param2)
        putU32le(payload, 6, timestampMs)
        return pack(SOF_CONTROL, Type.CONTROL_COMMAND, 0, payload)
    }
}
