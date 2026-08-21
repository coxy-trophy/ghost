package com.coxy.ghost.net

import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Controlling a UPnP device: playback and volume, over the same SOAP the device already speaks to
 * every media app on the network.
 *
 * There is no pairing and no credential here, because UPnP has none — a `MediaRenderer` accepts a
 * SetVolume from anything that can reach it. That is the protocol's design, not a hole this opens,
 * but it is why every action routed here has to name its target device explicitly rather than
 * resolving "the TV" to whichever renderer answered first.
 *
 * Two services do the work, and both were advertised by the televisions on this network:
 * - `AVTransport` — Play, Pause, Stop
 * - `RenderingControl` — volume and mute
 *
 * Control URLs are not guessable and are not the same between devices, so they are read from the
 * device description XML that SSDP's `LOCATION` header points at, and cached per device.
 */
object UpnpControl {

    private const val TAG = "UpnpControl"

    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    /** A control endpoint on one device: what it speaks, and where to say it. */
    data class Service(val type: String, val controlUrl: String)

    /**
     * Description documents change about never, and a control action would otherwise pay a fetch
     * and an XML parse before every "pause". Keyed by description URL, which is per device.
     */
    private val serviceCache = HashMap<String, Map<String, Service>>()

    fun invalidate() = synchronized(serviceCache) { serviceCache.clear() }

    /**
     * The services a device exposes, by service type.
     *
     * @param descriptionUrl the SSDP `LOCATION`, e.g. `http://192.168.100.24:8008/ssdp/device-desc.xml`
     */
    suspend fun services(descriptionUrl: String): Map<String, Service> =
        withContext(Dispatchers.IO) {
            synchronized(serviceCache) { serviceCache[descriptionUrl] }?.let { return@withContext it }
            val parsed = runCatching { fetchServices(descriptionUrl) }.getOrElse {
                Log.w(TAG, "could not read device description at $descriptionUrl", it)
                emptyMap()
            }
            synchronized(serviceCache) { serviceCache[descriptionUrl] = parsed }
            parsed
        }

    /**
     * Send one SOAP action.
     *
     * @return the response body on success, or null with the reason logged.
     */
    suspend fun invoke(
        service: Service,
        action: String,
        args: List<Pair<String, String>>
    ): Result<String> = withContext(Dispatchers.IO) {
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
            append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
            append("""<u:$action xmlns:u="${service.type}">""")
            for ((name, value) in args) append("<$name>${escape(value)}</$name>")
            append("</u:$action></s:Body></s:Envelope>")
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(service.controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"${service.type}#$action\"")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                // UPnP faults carry the useful part in errorCode; the HTTP status is always 500.
                val fault = Regex("<errorCode>(\\d+)</errorCode>").find(err)?.groupValues?.get(1)
                Log.w(TAG, "$action failed: HTTP $code fault=$fault")
                return@withContext Result.failure(
                    IOException(
                        when (fault) {
                            "401" -> "the device refused that action"
                            "402" -> "the device did not understand that request"
                            "501" -> "the device could not do it right now"
                            else -> "the device returned an error"
                        }
                    )
                )
            }
            Result.success(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "$action failed", e)
            Result.failure(IOException("could not reach the device (${e.javaClass.simpleName})"))
        } finally {
            conn?.disconnect()
        }
    }

    /** Pull a single value out of a SOAP response, e.g. `CurrentVolume`. */
    internal fun valueIn(response: String, tag: String): String? =
        Regex("<$tag>([^<]*)</$tag>").find(response)?.groupValues?.get(1)

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun fetchServices(descriptionUrl: String): Map<String, Service> {
        val conn = (URL(descriptionUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        val xml = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        return parseServices(xml, descriptionUrl)
    }

    /**
     * Parse `serviceType` / `controlURL` pairs out of a device description.
     *
     * `controlURL` is usually a path rather than an absolute URL, and `URLBase` — when present —
     * overrides the description's own origin as what it is relative to. Getting that wrong sends
     * every command to the right device on the wrong path, which fails as a 404 rather than as
     * anything that names the cause.
     */
    internal fun parseServices(xml: String, descriptionUrl: String): Map<String, Service> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(xml.reader())
        }
        val services = LinkedHashMap<String, Service>()
        var urlBase: String? = null
        var type: String? = null
        var controlUrl: String? = null
        var text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> text = StringBuilder()
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "URLBase" -> urlBase = text.toString().trim().ifEmpty { null }
                    "serviceType" -> type = text.toString().trim()
                    "controlURL" -> controlUrl = text.toString().trim()
                    "service" -> {
                        val t = type
                        val c = controlUrl
                        if (!t.isNullOrEmpty() && !c.isNullOrEmpty()) {
                            services[t] = Service(t, resolve(c, urlBase, descriptionUrl))
                        }
                        type = null
                        controlUrl = null
                    }
                }
            }
            event = parser.next()
        }
        return services
    }

    internal fun resolve(controlUrl: String, urlBase: String?, descriptionUrl: String): String {
        if (controlUrl.startsWith("http://") || controlUrl.startsWith("https://")) return controlUrl
        val base = urlBase?.trimEnd('/') ?: runCatching {
            val u = URL(descriptionUrl)
            "${u.protocol}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
        }.getOrDefault("")
        val path = if (controlUrl.startsWith("/")) controlUrl else "/$controlUrl"
        return "$base$path"
    }
}
