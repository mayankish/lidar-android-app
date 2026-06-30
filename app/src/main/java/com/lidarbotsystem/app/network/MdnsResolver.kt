package com.lidarbotsystem.app.network

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket

/**
 * Hand-rolled mDNS (RFC 6762) A-record resolver for "lidarbase.local".
 *
 * Why this exists instead of NsdManager: Android's NsdManager implements
 * DNS-SD *service discovery* (resolving `_servicetype._tcp.local` records
 * for things that advertise a service instance). base-radio
 * (esp32-raw-mac-radio/base-radio/main/main.c) only calls
 * `mdns_hostname_set("lidarbase")` -- it advertises a bare hostname, not a
 * registered NSD service type. Android has no public API for "resolve
 * this hostname.local to an IP" the way getaddrinfo()/nss-mdns does on
 * Linux/macOS, so the only way to ask the question base-radio actually
 * answers is to speak raw mDNS over a multicast UDP socket ourselves.
 *
 * This implementation handles exactly what it needs to: a single
 * QTYPE=A/QCLASS=IN question, and a response containing at least one
 * answer of TYPE=A, including basic DNS name-compression-pointer
 * following (the 0xC0 high-bit-pair prefix) since mDNS responders
 * commonly compress the question name back into the answer's NAME field.
 * It does not handle AAAA, multi-question queries, or truncated/multi-
 * packet responses -- none of which base-radio's simple responder
 * produces.
 */
object MdnsResolver {
    private const val MDNS_ADDRESS = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val DEFAULT_TIMEOUT_MS = 3000L

    /**
     * Resolves [hostnameLocal] (e.g. "lidarbase.local") to an [InetAddress],
     * or null on timeout/parse failure. Caller must hold a
     * [WifiManager.MulticastLock] for the duration of this call (acquired
     * around it, not inside it, so callers can reuse one lock across
     * multiple resolve attempts) and the app must hold the
     * CHANGE_WIFI_MULTICAST_STATE permission.
     */
    suspend fun resolve(context: Context, hostnameLocal: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): InetAddress? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                resolveBlocking(hostnameLocal)
            }
        }

    private fun resolveBlocking(hostnameLocal: String): InetAddress? {
        val group = InetAddress.getByName(MDNS_ADDRESS)
        MulticastSocket(0).use { socket ->
            socket.reuseAddress = true
            socket.timeToLive = 255
            socket.joinGroup(group)

            val query = buildQuery(hostnameLocal)
            val queryPacket = DatagramPacket(query, query.size, InetSocketAddress(group, MDNS_PORT))
            socket.send(queryPacket)

            val recvBuf = ByteArray(4096)
            // Loop until we see an answer for our own question, ignoring
            // other multicast chatter on the shared mDNS group/port -- or
            // until the outer withTimeoutOrNull cancels this coroutine.
            while (true) {
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPacket)
                val addr = parseAResponse(recvBuf, recvPacket.length, hostnameLocal)
                if (addr != null) return addr
            }
        }
    }

    private fun encodeName(hostname: String): ByteArray {
        val out = mutableListOf<Byte>()
        for (label in hostname.split(".")) {
            if (label.isEmpty()) continue
            out.add(label.length.toByte())
            for (c in label.toByteArray(Charsets.US_ASCII)) out.add(c)
        }
        out.add(0) // root terminator
        return out.toByteArray()
    }

    private fun buildQuery(hostnameLocal: String): ByteArray {
        val name = encodeName(hostnameLocal)
        val buf = ByteArray(12 + name.size + 4)
        // Header: ID=0 (mDNS ignores it), flags=0 (standard query), QDCOUNT=1
        buf[0] = 0; buf[1] = 0          // ID
        buf[2] = 0; buf[3] = 0          // flags
        buf[4] = 0; buf[5] = 1          // QDCOUNT = 1
        buf[6] = 0; buf[7] = 0          // ANCOUNT
        buf[8] = 0; buf[9] = 0          // NSCOUNT
        buf[10] = 0; buf[11] = 0        // ARCOUNT
        System.arraycopy(name, 0, buf, 12, name.size)
        val qOff = 12 + name.size
        buf[qOff] = 0; buf[qOff + 1] = 1     // QTYPE = A (1)
        buf[qOff + 2] = 0; buf[qOff + 3] = 1 // QCLASS = IN (1), unicast-response bit left clear
        return buf
    }

    /** Reads a (possibly compressed) DNS name starting at [offset], returning
     * the decoded name and the offset immediately after it in the *original*
     * record (i.e. after a compression pointer, not after the pointed-to
     * data). Follows at most one level of indirection chain, which is all
     * a flat single-question/single-answer mDNS response ever needs. */
    private fun readName(buf: ByteArray, startOffset: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var offset = startOffset
        var endOffset = -1 // set on first pointer jump; that's where the *caller* should resume
        var guard = 0
        while (guard++ < 128) {
            val len = buf[offset].toInt() and 0xFF
            if (len == 0) {
                offset += 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                // compression pointer: 14-bit offset from low 6 bits of this
                // byte + all 8 bits of the next byte
                val pointer = ((len and 0x3F) shl 8) or (buf[offset + 1].toInt() and 0xFF)
                if (endOffset < 0) endOffset = offset + 2
                offset = pointer
                continue
            }
            val label = String(buf, offset + 1, len, Charsets.US_ASCII)
            labels.add(label)
            offset += 1 + len
        }
        val finalOffset = if (endOffset >= 0) endOffset else offset
        return Pair(labels.joinToString("."), finalOffset)
    }

    private fun parseAResponse(buf: ByteArray, len: Int, hostnameLocal: String): InetAddress? {
        if (len < 12) return null
        val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        if (anCount <= 0) return null

        var offset = 12
        // Skip the question section.
        repeat(qdCount) {
            val (_, afterName) = readName(buf, offset)
            offset = afterName + 4 // QTYPE(2) + QCLASS(2)
        }

        repeat(anCount) {
            val (name, afterName) = readName(buf, offset)
            offset = afterName
            if (offset + 10 > len) return null
            val type = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
            // class field at offset+2..3 intentionally ignored: mDNS sets
            // the cache-flush bit (top bit) on class IN, which a strict
            // == 1 check would reject.
            val rdLength = ((buf[offset + 8].toInt() and 0xFF) shl 8) or (buf[offset + 9].toInt() and 0xFF)
            val rdataOffset = offset + 10
            offset = rdataOffset + rdLength

            val targetMatches = name.equals(hostnameLocal, ignoreCase = true) || name.isEmpty()
            if (type == 1 && rdLength == 4 && targetMatches) { // TYPE A
                val ipBytes = byteArrayOf(
                    buf[rdataOffset], buf[rdataOffset + 1], buf[rdataOffset + 2], buf[rdataOffset + 3],
                )
                return InetAddress.getByAddress(ipBytes)
            }
        }
        return null
    }

    /** Convenience wrapper matching the lifecycle the ViewModel needs:
     * acquire the multicast lock, resolve, release, regardless of outcome. */
    suspend fun resolveWithLock(context: Context, hostnameLocal: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): InetAddress? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiManager.createMulticastLock("lidar-bot-system-mdns")
        lock.setReferenceCounted(true)
        lock.acquire()
        return try {
            resolve(context, hostnameLocal, timeoutMs)
        } finally {
            lock.release()
        }
    }
}
