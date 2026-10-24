package com.xera.ptzcam

import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Shares the live video with a laptop on the same Wi-Fi / hotspot as this device.
 *
 * Why a relay: on the Skydroid link the camera lives on the radio network (192.168.144.x)
 * while the laptop joins the G20's Wi-Fi; Android does not route between the two, so the
 * laptop cannot reach the camera directly. This server listens on [port] on every interface
 * and forwards each RTSP connection byte-for-byte to the camera's RTSP server, so the laptop
 * receives the original stream (no re-encoding, negligible CPU).
 *
 * RTSP over TCP is required on the laptop (VLC falls back to it automatically, or start VLC
 * with --rtsp-tcp): UDP media would be sent by the camera to an address it cannot reach.
 *
 * Pure java.net (no Android) so it is unit-testable.
 */
class VideoShareServer(
    val port: Int,
    /** Camera RTSP host and port to forward to (read for every new connection). */
    private val target: () -> Pair<String, Int>?,
    private val onEvent: (String) -> Unit = {},
) {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val clients = AtomicInteger(0)
    private val sockets = Collections.synchronizedSet(HashSet<Socket>())

    val isRunning get() = running
    val clientCount get() = clients.get()

    /** Starts listening; throws if the port is in use or not allowed. */
    fun start() {
        if (running) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))          // all interfaces (Wi-Fi, hotspot, Ethernet)
        server = s
        running = true
        thread(name = "share-accept", isDaemon = true) { acceptLoop(s) }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        synchronized(sockets) {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val client = try {
                s.accept()
            } catch (e: SocketException) {
                break                               // closed by stop()
            } catch (e: Exception) {
                continue
            }
            if (clients.get() >= MAX_CLIENTS) {
                runCatching { client.close() }
                onEvent("Laptop refused – $MAX_CLIENTS viewers already connected")
                continue
            }
            thread(name = "share-client", isDaemon = true) { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        val dest = target()
        if (dest == null) {
            runCatching { client.close() }
            onEvent("Laptop connected, but no camera stream is configured yet")
            return
        }
        val upstream = Socket()
        try {
            upstream.connect(InetSocketAddress(dest.first, dest.second), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            runCatching { client.close() }
            runCatching { upstream.close() }
            onEvent("Laptop connected, but the camera at ${dest.first}:${dest.second} did not answer")
            return
        }
        listOf(client, upstream).forEach {
            it.tcpNoDelay = true
            it.keepAlive = true
            sockets.add(it)
        }
        val who = client.inetAddress?.hostAddress ?: "laptop"
        onEvent("Laptop $who is watching (${clients.incrementAndGet()} viewer${if (clients.get() == 1) "" else "s"})")

        // two pumps; when either side closes, close both
        val done = AtomicInteger(0)
        fun finish() {
            if (done.incrementAndGet() == 1) {
                runCatching { client.close() }
                runCatching { upstream.close() }
                sockets.remove(client)
                sockets.remove(upstream)
                val left = clients.decrementAndGet()
                if (running) onEvent("Laptop $who stopped watching ($left left)")
            }
        }
        thread(name = "share-up", isDaemon = true) {
            pump(client.getInputStream(), upstream.getOutputStream())
            finish()
        }
        pump(upstream.getInputStream(), client.getOutputStream())
        finish()
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
            // connection closed
        }
    }

    /** A network address of this device, classified for laptop sharing. */
    data class LocalAddress(val ip: String, val kind: Kind, val iface: String)

    enum class Kind { HOTSPOT, WIFI, CAMERA_LINK, OTHER }

    companion object {
        const val DEFAULT_PORT = 8554
        const val MAX_CLIENTS = 4
        private const val CONNECT_TIMEOUT_MS = 4000

        /** "rtsp://user:pass@host:port/path?q" → (host, port). Default RTSP port 554. */
        fun parseTarget(url: String): Pair<String, Int>? {
            val m = Regex("^rtsps?://(?:[^@/]*@)?([^:/?#]+)(?::(\\d+))?", RegexOption.IGNORE_CASE)
                .find(url.trim()) ?: return null
            return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 554)
        }

        /** Path + query of an RTSP URL ("/video1", "/Streaming/Channels/101?x=1"), without credentials. */
        fun pathOf(url: String): String {
            val m = Regex("^rtsps?://[^/]*(/.*)?$", RegexOption.IGNORE_CASE).find(url.trim()) ?: return "/"
            return m.groupValues[1].ifEmpty { "/" }
        }

        /**
         * Classifies an interface. Android names its hotspot interface ap0 / swlan0 / softap0 /
         * wlan1 depending on the device; a hotspot is also the gateway (.1) of its own subnet.
         * The Skydroid camera link (192.168.144.x) is never offered to the laptop.
         */
        fun classify(iface: String, ip: String): Kind {
            val n = iface.lowercase()
            return when {
                ip.startsWith("192.168.144.") -> Kind.CAMERA_LINK
                n.startsWith("ap") || n.startsWith("swlan") || n.contains("softap") || n.startsWith("wlan1") -> Kind.HOTSPOT
                n.startsWith("wlan") && ip.endsWith(".1") -> Kind.HOTSPOT
                n.startsWith("wlan") -> Kind.WIFI
                else -> Kind.OTHER
            }
        }

        /** IPv4 addresses of this device (excluding loopback / link-local), hotspot first. */
        fun localAddresses(): List<LocalAddress> {
            val out = ArrayList<LocalAddress>()
            val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return out
            for (ni in ifaces) {
                if (!runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)) continue
                val name = ni.name ?: continue
                for (addr in ni.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        out.add(LocalAddress(ip, classify(name, ip), name))
                    }
                }
            }
            return out.sortedBy { it.kind.ordinal }
        }
    }
}
