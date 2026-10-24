package com.xera.ptzcam

import android.util.Base64
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class OnvifException(message: String) : Exception(message)

/** Camera position in ONVIF generic space: pan/tilt -1..1, zoom 0..1 (null if not reported). */
data class PtzStatus(val pan: Float?, val tilt: Float?, val zoom: Float?)

/** Absolute position ranges reported by the camera (GetNodes). Null fields = not reported. */
data class PtzRanges(
    val panMin: Float?, val panMax: Float?,
    val tiltMin: Float?, val tiltMax: Float?,
    val zoomMin: Float?, val zoomMax: Float?,
)

/**
 * Minimal ONVIF client: service discovery, RTSP URI, PTZ moves, presets, home position,
 * night mode (IR-cut filter + IR lamp) and JPEG snapshots.
 * All methods are BLOCKING – call them from Dispatchers.IO.
 *
 * Auth: WS-Security UsernameToken (PasswordDigest), with HTTP Digest / Basic fallback.
 */
class OnvifClient(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val pass: String,
) : PtzMover {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val deviceUrl = "http://$host:$port/onvif/device_service"
    private var mediaUrl = deviceUrl
    private var ptzUrl = deviceUrl
    private var imagingUrl: String? = null
    private var profileToken = ""
    private var videoSourceToken: String? = null
    private var snapshotUri: String? = null
    private var clockOffsetMs = 0L
    @Volatile private var nonceCount = 0

    /** Auxiliary commands the camera reports (e.g. "tt:Wiper|On"). Filled by connect(). */
    var auxCommands: List<String> = emptyList()
        private set

    /** Position ranges of the PTZ node (used to convert status values to degrees). */
    var ranges: PtzRanges? = null
        private set

    /** Connects and returns the RTSP URI of the first profile (null if the camera gives none). */
    fun connect(): String? {
        syncClock()

        val caps = soap(
            deviceUrl,
            "<tds:GetCapabilities xmlns:tds=\"$NS_DEVICE\"><tds:Category>All</tds:Category></tds:GetCapabilities>"
        )
        xaddr(caps, "Media")?.let { mediaUrl = withHost(it) }
        xaddr(caps, "PTZ")?.let { ptzUrl = withHost(it) }
        xaddr(caps, "Imaging")?.let { imagingUrl = withHost(it) }

        val profiles = soap(mediaUrl, "<trt:GetProfiles xmlns:trt=\"$NS_MEDIA\"/>")
        profileToken = Regex("<(?:\\w+:)?Profiles\\b[^>]*\\btoken=\"([^\"]+)\"")
            .find(profiles)?.groupValues?.get(1)
            ?: throw OnvifException("camera returned no media profile")
        videoSourceToken = Regex("<(?:\\w+:)?SourceToken>([^<]+)<")
            .find(profiles)?.groupValues?.get(1)?.trim()

        val nodes = try {
            soap(ptzUrl, "<tptz:GetNodes xmlns:tptz=\"$NS_PTZ\"/>")
        } catch (e: Exception) {
            ""
        }
        auxCommands = Regex("<(?:\\w+:)?AuxiliaryCommands>([^<]+)<").findAll(nodes)
            .map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()
        ranges = parseRanges(nodes)

        return try {
            val r = soap(
                mediaUrl,
                "<trt:GetStreamUri xmlns:trt=\"$NS_MEDIA\" xmlns:tt=\"$NS_SCHEMA\">" +
                    "<trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
                    "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
                    "<trt:ProfileToken>$profileToken</trt:ProfileToken></trt:GetStreamUri>"
            )
            findUri(r)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ PTZ

    /** Speeds in -1..1. Camera keeps moving until stop() is sent. */
    override fun continuousMove(pan: Float, tilt: Float, zoom: Float) {
        soap(
            ptzUrl,
            "<tptz:ContinuousMove xmlns:tptz=\"$NS_PTZ\" xmlns:tt=\"$NS_SCHEMA\">" +
                "<tptz:ProfileToken>$profileToken</tptz:ProfileToken><tptz:Velocity>" +
                "<tt:PanTilt x=\"${num(pan)}\" y=\"${num(tilt)}\"/><tt:Zoom x=\"${num(zoom)}\"/>" +
                "</tptz:Velocity></tptz:ContinuousMove>"
        )
    }

    override fun stop() {
        soap(
            ptzUrl,
            "<tptz:Stop xmlns:tptz=\"$NS_PTZ\"><tptz:ProfileToken>$profileToken</tptz:ProfileToken>" +
                "<tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>"
        )
    }

    fun gotoPreset(token: String) {
        soap(
            ptzUrl,
            "<tptz:GotoPreset xmlns:tptz=\"$NS_PTZ\"><tptz:ProfileToken>$profileToken</tptz:ProfileToken>" +
                "<tptz:PresetToken>${esc(token)}</tptz:PresetToken></tptz:GotoPreset>"
        )
    }

    /** Saves the current position. token == null lets the camera choose. Returns the preset token. */
    fun setPreset(token: String?, name: String): String {
        val tokenXml = token?.let { "<tptz:PresetToken>${esc(it)}</tptz:PresetToken>" } ?: ""
        val r = soap(
            ptzUrl,
            "<tptz:SetPreset xmlns:tptz=\"$NS_PTZ\"><tptz:ProfileToken>$profileToken</tptz:ProfileToken>" +
                "<tptz:PresetName>${esc(name)}</tptz:PresetName>$tokenXml</tptz:SetPreset>"
        )
        return Regex("<(?:\\w+:)?PresetToken>([^<]+)<").find(r)?.groupValues?.get(1)?.trim()
            ?: token ?: throw OnvifException("camera did not return a preset token")
    }

    fun getStatus(): PtzStatus {
        val r = soap(
            ptzUrl,
            "<tptz:GetStatus xmlns:tptz=\"$NS_PTZ\"><tptz:ProfileToken>$profileToken</tptz:ProfileToken>" +
                "</tptz:GetStatus>"
        )
        val pos = r.substringAfter("Position", "")
        val pt = Regex("<(?:\\w+:)?PanTilt\\b([^>]*)>").find(pos)?.groupValues?.get(1)
        val zm = Regex("<(?:\\w+:)?Zoom\\b([^>]*)>").find(pos)?.groupValues?.get(1)
        fun attr(tag: String?, name: String): Float? = tag?.let {
            Regex("\\b$name=\"([-+0-9.eE]+)\"").find(it)?.groupValues?.get(1)?.toFloatOrNull()
        }
        return PtzStatus(attr(pt, "x"), attr(pt, "y"), attr(zm, "x"))
    }

    fun gotoHome() {
        soap(
            ptzUrl,
            "<tptz:GotoHomePosition xmlns:tptz=\"$NS_PTZ\"><tptz:ProfileToken>$profileToken</tptz:ProfileToken>" +
                "</tptz:GotoHomePosition>"
        )
    }

    fun setHome() {
        soap(
            ptzUrl,
            "<tptz:SetHomePosition xmlns:tptz=\"$NS_PTZ\"><tptz:ProfileToken>$profileToken</tptz:ProfileToken>" +
                "</tptz:SetHomePosition>"
        )
    }

    /** ONVIF auxiliary command, e.g. "tt:IRLamp|On". */
    fun auxCommand(cmd: String) {
        soap(
            ptzUrl,
            "<tptz:SendAuxiliaryCommand xmlns:tptz=\"$NS_PTZ\"><tptz:ProfileToken>$profileToken</tptz:ProfileToken>" +
                "<tptz:AuxiliaryData>${esc(cmd)}</tptz:AuxiliaryData></tptz:SendAuxiliaryCommand>"
        )
    }

    // ------------------------------------------------------------------ imaging (night mode)

    /** Returns "ON" (day / colour), "OFF" (night / IR) or "AUTO"; null if unknown. */
    fun getIrCutFilter(): String? {
        val url = imagingUrl ?: return null
        val tok = videoSourceToken ?: return null
        val r = soap(
            url,
            "<timg:GetImagingSettings xmlns:timg=\"$NS_IMAGING\">" +
                "<timg:VideoSourceToken>$tok</timg:VideoSourceToken></timg:GetImagingSettings>"
        )
        return Regex("<(?:\\w+:)?IrCutFilter>([^<]+)<").find(r)?.groupValues?.get(1)?.trim()?.uppercase(Locale.US)
    }

    /** mode: "ON" = day (filter in, colour), "OFF" = night (filter out, IR), "AUTO". */
    fun setIrCutFilter(mode: String) {
        val url = imagingUrl ?: throw OnvifException("camera has no imaging service")
        val tok = videoSourceToken ?: throw OnvifException("no video source token")
        soap(
            url,
            "<timg:SetImagingSettings xmlns:timg=\"$NS_IMAGING\" xmlns:tt=\"$NS_SCHEMA\">" +
                "<timg:VideoSourceToken>$tok</timg:VideoSourceToken>" +
                "<timg:ImagingSettings><tt:IrCutFilter>$mode</tt:IrCutFilter></timg:ImagingSettings>" +
                "<timg:ForcePersistence>true</timg:ForcePersistence></timg:SetImagingSettings>"
        )
    }

    // ------------------------------------------------------------------ snapshot

    /** Full-resolution JPEG from the camera. */
    fun snapshot(): ByteArray {
        val uri = snapshotUri ?: run {
            val r = soap(
                mediaUrl,
                "<trt:GetSnapshotUri xmlns:trt=\"$NS_MEDIA\"><trt:ProfileToken>$profileToken</trt:ProfileToken>" +
                    "</trt:GetSnapshotUri>"
            )
            findUri(r) ?: throw OnvifException("camera has no snapshot URI")
        }.also { snapshotUri = it }

        val bytes = request(uri, null)
        if (bytes.size < 1000 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
            throw OnvifException("snapshot is not a JPEG")
        }
        return bytes
    }

    // ------------------------------------------------------------------ internals

    /** WS-Security timestamps must match the camera clock, so measure the offset first. */
    private fun syncClock() {
        try {
            val r = String(
                request(deviceUrl, { envelope("<tds:GetSystemDateAndTime xmlns:tds=\"$NS_DEVICE\"/>", secure = false) }),
                Charsets.UTF_8
            )
            val utc = r.substringAfter("UTCDateTime", "")
            if (utc.isEmpty()) return
            fun v(tag: String) = Regex("<(?:\\w+:)?$tag>(\\d+)<").find(utc)?.groupValues?.get(1)?.toInt()
            val year = v("Year") ?: return
            val month = v("Month") ?: return
            val day = v("Day") ?: return
            val hour = v("Hour") ?: return
            val minute = v("Minute") ?: return
            val second = v("Second") ?: return
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(year, month - 1, day, hour, minute, second)
            clockOffsetMs = cal.timeInMillis - System.currentTimeMillis()
        } catch (_: Exception) {
            // not fatal
        }
    }

    private fun soap(url: String, body: String): String =
        String(request(url, { envelope(body) }), Charsets.UTF_8)

    private fun envelope(body: String, secure: Boolean = true): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"$NS_SOAP\">" +
            (if (secure) securityHeader() else "") +
            "<s:Body>$body</s:Body></s:Envelope>"

    /** POST (when body != null) or GET, with automatic Digest/Basic retry on 401. */
    private fun request(url: String, body: (() -> String)?, authorization: String? = null): ByteArray {
        val builder = Request.Builder().url(url)
        if (body != null) builder.post(body().toRequestBody(SOAP_TYPE)) else builder.get()
        authorization?.let { builder.header("Authorization", it) }

        return http.newCall(builder.build()).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (resp.code == 401 && authorization == null) {
                val challenges = resp.headers("WWW-Authenticate")
                val digest = challenges.firstOrNull { it.startsWith("Digest", ignoreCase = true) }
                val method = if (body != null) "POST" else "GET"
                if (digest != null) return request(url, body, digestAuthorization(digest, url, method))
                if (challenges.any { it.startsWith("Basic", ignoreCase = true) }) {
                    return request(url, body, Credentials.basic(user, pass))
                }
            }
            if (!resp.isSuccessful) {
                val text = String(bytes, Charsets.UTF_8)
                val reason = Regex("<(?:\\w+:)?Text[^>]*>([^<]+)<").find(text)?.groupValues?.get(1)
                throw OnvifException("HTTP ${resp.code}" + (reason?.let { " – $it" } ?: ""))
            }
            bytes
        }
    }

    private fun securityHeader(): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val created = fmt.format(Date(System.currentTimeMillis() + clockOffsetMs))
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonce)
        sha1.update(created.toByteArray(Charsets.UTF_8))
        sha1.update(pass.toByteArray(Charsets.UTF_8))
        val digest = Base64.encodeToString(sha1.digest(), Base64.NO_WRAP)
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)

        return "<s:Header><wsse:Security s:mustUnderstand=\"1\" xmlns:wsse=\"$NS_WSSE\" xmlns:wsu=\"$NS_WSU\">" +
            "<wsse:UsernameToken><wsse:Username>${esc(user)}</wsse:Username>" +
            "<wsse:Password Type=\"$PASSWORD_DIGEST\">$digest</wsse:Password>" +
            "<wsse:Nonce EncodingType=\"$BASE64_BINARY\">$nonceB64</wsse:Nonce>" +
            "<wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security></s:Header>"
    }

    private fun digestAuthorization(challenge: String, url: String, method: String): String {
        fun param(key: String) =
            Regex("\\b$key=\"?([^\",]+)\"?", RegexOption.IGNORE_CASE).find(challenge)?.groupValues?.get(1)

        val realm = param("realm") ?: ""
        val nonce = param("nonce") ?: ""
        val opaque = param("opaque")
        val qop = Regex("\\bqop=\"?([^\"]+)\"?", RegexOption.IGNORE_CASE).find(challenge)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val u = URI(url)
        val uri = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")

        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$uri")
        val cnonce = md5(System.nanoTime().toString()).take(16)
        val nc = String.format(Locale.US, "%08x", ++nonceCount)
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")

        val sb = StringBuilder()
        sb.append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
        if (qop != null) sb.append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
        if (opaque != null) sb.append(", opaque=\"$opaque\"")
        return sb.toString()
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it) }

    private fun parseRanges(nodes: String): PtzRanges? {
        if (nodes.isEmpty()) return null
        fun range(block: String, axis: String): Pair<Float?, Float?> {
            val m = Regex(
                "<(?:\\w+:)?$axis>\\s*<(?:\\w+:)?Min>\\s*([-+0-9.eE]+)\\s*</(?:\\w+:)?Min>\\s*" +
                    "<(?:\\w+:)?Max>\\s*([-+0-9.eE]+)\\s*<"
            ).find(block) ?: return null to null
            return m.groupValues[1].toFloatOrNull() to m.groupValues[2].toFloatOrNull()
        }
        val pt = nodes.substringAfter("AbsolutePanTiltPositionSpace", "")
        val zm = nodes.substringAfter("AbsoluteZoomPositionSpace", "")
        val (pMin, pMax) = range(pt, "XRange")
        val (tMin, tMax) = range(pt, "YRange")
        val (zMin, zMax) = range(zm, "XRange")
        return PtzRanges(pMin, pMax, tMin, tMax, zMin, zMax)
    }

    private fun findUri(xml: String): String? =
        Regex("<(?:\\w+:)?Uri>([^<]+)</").find(xml)?.groupValues?.get(1)
            ?.trim()?.replace("&amp;", "&")?.let { withHost(it) }

    private fun xaddr(xml: String, section: String): String? =
        Regex("<(?:\\w+:)?$section>\\s*<(?:\\w+:)?XAddr>([^<]+)<").find(xml)?.groupValues?.get(1)?.trim()

    /** Cameras often report their own (old) IP in URLs – force the address we actually reach them on. */
    private fun withHost(url: String): String =
        Regex("^(\\w+://)([^/:@]+)").replace(url.trim()) { it.groupValues[1] + host }

    private fun num(v: Float) = String.format(Locale.US, "%.2f", v)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    companion object {
        private val SOAP_TYPE = "application/soap+xml; charset=utf-8".toMediaType()
        private const val NS_SOAP = "http://www.w3.org/2003/05/soap-envelope"
        private const val NS_DEVICE = "http://www.onvif.org/ver10/device/wsdl"
        private const val NS_MEDIA = "http://www.onvif.org/ver10/media/wsdl"
        private const val NS_PTZ = "http://www.onvif.org/ver20/ptz/wsdl"
        private const val NS_IMAGING = "http://www.onvif.org/ver20/imaging/wsdl"
        private const val NS_SCHEMA = "http://www.onvif.org/ver10/schema"
        private const val NS_WSSE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
        private const val NS_WSU =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
        private const val PASSWORD_DIGEST =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest"
        private const val BASE64_BINARY =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary"
    }
}
