package com.xera.ptzcam

import android.os.SystemClock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Locale
import kotlin.concurrent.thread

/** Anything the PTZ loop can drive continuously (ONVIF camera or SIYI gimbal). */
interface PtzMover {
    /** pan / tilt / zoom speeds in -1..1 (pan + = right, tilt + = up, zoom + = in). */
    fun continuousMove(pan: Float, tilt: Float, zoom: Float)
    fun stop()
}

/**
 * SIYI gimbal SDK over UDP (ZT6, ZT30, ZR10, A8 …). Default 192.168.144.25:37260.
 *
 * Packet: 0x55 0x66 | CTRL | DATA_LEN (u16 LE) | SEQ (u16 LE) | CMD_ID | DATA | CRC16 (LE)
 * CRC16-CCITT (poly 0x1021, init 0) over everything before the CRC.
 * Command ids and payloads follow SIYI's SDK / ArduPilot's AP_Mount_Siyi driver.
 * Send methods are BLOCKING network calls – use them from Dispatchers.IO.
 */
class SiyiClient(private val host: String, private val port: Int) : PtzMover {

    data class Attitude(val yaw: Float, val pitch: Float, val roll: Float)

    /** Gimbal angles in degrees (SIYI frame: yaw + = left, pitch + = up). */
    @Volatile var attitude: Attitude? = null
        private set
    @Volatile var attitudeTime = 0L
        private set
    @Volatile var zoomX: Float? = null
        private set
    @Volatile var tempMax: Float? = null
        private set
    @Volatile var tempMin: Float? = null
        private set
    @Volatile var tempTime = 0L
        private set
    /** 0 = off, 1 = recording, 2 = no SD card, 3 = data loss (from config reply). */
    @Volatile var recordStatus: Int? = null
        private set
    /** 0 = lock, 1 = follow, 2 = FPV. */
    @Volatile var motionMode: Int? = null
        private set
    @Volatile var hardwareId: String? = null
        private set
    @Volatile var lastRxTime = 0L
        private set

    /** Function feedback (photo / record results) and debug lines. */
    var onFeedback: ((String) -> Unit)? = null
    var onDebug: ((String) -> Unit)? = null

    private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private var seq = 0
    private var lastZoomDir = 0

    val modelName: String
        get() = when (hardwareId?.take(2)) {
            "83" -> "ZT6"
            "7A", "7a" -> "ZT30"
            "6B", "6b" -> "ZR10"
            "73" -> "A8 mini"
            "78" -> "ZR30"
            null -> "SIYI"
            else -> "SIYI (${hardwareId?.take(2)})"
        }

    // ------------------------------------------------------------------ connection

    fun open() {
        close()
        val s = DatagramSocket()
        s.soTimeout = 500
        s.connect(InetSocketAddress(host, port))
        socket = s
        running = true
        thread(name = "siyi-rx", isDaemon = true) { rxLoop(s) }
    }

    fun close() {
        running = false
        socket?.close()
        socket = null
    }

    /** Sends identification + attitude requests and waits for any reply. */
    fun handshake(timeoutMs: Long = 2500): Boolean {
        val start = SystemClock.elapsedRealtime()
        requestHardwareId()
        requestAttitude()
        requestConfig()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (lastRxTime >= start) return true
            Thread.sleep(50)
        }
        return false
    }

    // ------------------------------------------------------------------ commands

    fun requestHardwareId() = send(CMD_HARDWARE_ID)
    fun requestAttitude() = send(CMD_ATTITUDE)
    fun requestConfig() = send(CMD_CONFIG)

    /** Continuous thermal max / min report (≈5 Hz); re-send if it stops. */
    fun requestThermalStream() = send(CMD_TEMP_FULL_IMAGE, byteArrayOf(2))

    fun center() = send(CMD_CENTER, byteArrayOf(1))

    /** yaw / pitch speed scalars -100..100 (yaw + = right, pitch + = up). */
    fun rotate(yaw: Int, pitch: Int) =
        send(CMD_ROTATION, byteArrayOf(yaw.coerceIn(-100, 100).toByte(), pitch.coerceIn(-100, 100).toByte()))

    /** +1 zoom in, -1 zoom out, 0 stop. */
    fun zoomRate(dir: Int) {
        val b = when {
            dir > 0 -> 1
            dir < 0 -> 0xFF
            else -> 0
        }
        send(CMD_MANUAL_ZOOM, byteArrayOf(b.toByte()))
        lastZoomDir = dir
    }

    fun takePhoto() = send(CMD_PHOTO, byteArrayOf(0))

    /** Toggles recording on the gimbal's SD card. */
    fun toggleSdRecording() = send(CMD_PHOTO, byteArrayOf(2))

    /** 0 = lock, 1 = follow, 2 = FPV. */
    fun setMotionMode(mode: Int) {
        send(CMD_PHOTO, byteArrayOf((3 + mode.coerceIn(0, 2)).toByte()))
        motionMode = mode
    }

    /** ZT6: 3 = RGB main / IR sub, 7 = IR main / RGB sub, 0 = picture-in-picture. */
    fun setImageType(type: Int) = send(CMD_IMAGE_TYPE, byteArrayOf(type.toByte()))

    /** 0 WhiteHot, 2 Sepia, 3 IronBow, 4 Rainbow, 5 Night, 6 Aurora, 7 RedHot, 8 Jungle, 9 Medical, 10 BlackHot, 11 GloryHot. */
    fun setPalette(palette: Int) = send(CMD_THERMAL_PALETTE, byteArrayOf(palette.toByte()))

    override fun continuousMove(pan: Float, tilt: Float, zoom: Float) {
        rotate((pan * 100).toInt(), (tilt * 100).toInt())
        val dir = when {
            zoom > 0.15f -> 1
            zoom < -0.15f -> -1
            else -> 0
        }
        if (dir != lastZoomDir) zoomRate(dir)
    }

    override fun stop() {
        rotate(0, 0)
        if (lastZoomDir != 0) zoomRate(0)
    }

    // ------------------------------------------------------------------ packet I/O

    @Synchronized
    private fun send(cmd: Int, data: ByteArray = ByteArray(0)) {
        val s = socket ?: throw IOException("gimbal not connected")
        val len = data.size
        val buf = ByteArray(10 + len)
        buf[0] = 0x55
        buf[1] = 0x66
        buf[2] = 1                                   // CTRL: request ACK
        buf[3] = (len and 0xFF).toByte()
        buf[4] = ((len shr 8) and 0xFF).toByte()
        buf[5] = (seq and 0xFF).toByte()
        buf[6] = ((seq shr 8) and 0xFF).toByte()
        seq = (seq + 1) and 0xFFFF
        buf[7] = cmd.toByte()
        System.arraycopy(data, 0, buf, 8, len)
        val crc = crc16(buf, 8 + len)
        buf[8 + len] = (crc and 0xFF).toByte()
        buf[9 + len] = ((crc shr 8) and 0xFF).toByte()
        s.send(DatagramPacket(buf, buf.size))
        if (cmd != CMD_ATTITUDE && cmd != CMD_ROTATION) {
            onDebug?.invoke("TX " + hex(buf, buf.size))
        }
    }

    private fun rxLoop(s: DatagramSocket) {
        val buf = ByteArray(1024)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                parse(buf, p.length)
            } catch (_: SocketTimeoutException) {
                // keep waiting
            } catch (e: Exception) {
                if (!running) break
            }
        }
    }

    private fun parse(b: ByteArray, n: Int) {
        var i = 0
        while (i + 10 <= n) {
            if (b[i] != 0x55.toByte() || b[i + 1] != 0x66.toByte()) { i++; continue }
            val len = u8(b, i + 3) or (u8(b, i + 4) shl 8)
            val total = 10 + len
            if (i + total > n) return
            val crc = u8(b, i + 8 + len) or (u8(b, i + 9 + len) shl 8)
            val calc = crc16(b.copyOfRange(i, i + 8 + len), 8 + len)
            if (crc == calc) {
                handle(u8(b, i + 7), b, i + 8, len)
            }
            i += total
        }
    }

    private fun handle(cmd: Int, b: ByteArray, d: Int, len: Int) {
        val now = SystemClock.elapsedRealtime()
        lastRxTime = now
        when (cmd) {
            CMD_ATTITUDE -> if (len >= 6) {
                attitude = Attitude(s16(b, d) / 10f, s16(b, d + 2) / 10f, s16(b, d + 4) / 10f)
                attitudeTime = now
            }
            CMD_MANUAL_ZOOM -> if (len >= 2) {
                val z = u16(b, d) / 10f
                if (z > 0f) zoomX = z
            }
            CMD_TEMP_FULL_IMAGE -> if (len >= 4) {
                tempMax = s16(b, d) / 100f
                tempMin = s16(b, d + 2) / 100f
                tempTime = now
            }
            CMD_CONFIG -> if (len >= 5) {
                recordStatus = u8(b, d + 3)
                motionMode = u8(b, d + 4)
            }
            CMD_HARDWARE_ID -> if (len > 0) {
                hardwareId = String(b, d, len, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                onDebug?.invoke("RX hardware id $hardwareId")
            }
            CMD_FUNCTION_FEEDBACK -> if (len >= 1) {
                val msg = when (u8(b, d)) {
                    0 -> "Photo saved on gimbal SD card"
                    1 -> "Gimbal photo failed – check SD card"
                    4 -> "Gimbal recording failed – check SD card"
                    5 -> "Gimbal SD recording started"
                    6 -> "Gimbal SD recording stopped"
                    else -> null
                }
                if (msg != null) onFeedback?.invoke(msg)
            }
        }
        if (cmd != CMD_ATTITUDE && cmd != CMD_TEMP_FULL_IMAGE) {
            onDebug?.invoke(String.format(Locale.US, "RX cmd 0x%02X len %d", cmd, len))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()

    private fun hex(b: ByteArray, n: Int) =
        (0 until n).joinToString(" ") { String.format(Locale.US, "%02X", b[it].toInt() and 0xFF) }

    companion object {
        const val DEFAULT_PORT = 37260

        private const val CMD_HARDWARE_ID = 0x02
        private const val CMD_MANUAL_ZOOM = 0x05
        private const val CMD_ROTATION = 0x07
        private const val CMD_CENTER = 0x08
        private const val CMD_CONFIG = 0x0A
        private const val CMD_FUNCTION_FEEDBACK = 0x0B
        private const val CMD_PHOTO = 0x0C
        private const val CMD_ATTITUDE = 0x0D
        private const val CMD_IMAGE_TYPE = 0x11
        private const val CMD_TEMP_FULL_IMAGE = 0x14
        private const val CMD_THERMAL_PALETTE = 0x1B

        /** CRC16-CCITT (XMODEM): poly 0x1021, init 0 – verified against SIYI's sample packet. */
        fun crc16(data: ByteArray, len: Int): Int {
            var crc = 0
            for (k in 0 until len) {
                crc = crc xor ((data[k].toInt() and 0xFF) shl 8)
                repeat(8) {
                    crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                    crc = crc and 0xFFFF
                }
            }
            return crc
        }
    }
}
