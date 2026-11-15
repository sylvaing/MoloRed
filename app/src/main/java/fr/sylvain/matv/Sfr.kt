package fr.sylvain.matv

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Endpoints and constants of the SFR TV web player (see docs/sfr-tv-api.md). */
object Sfr {
    const val CLIENT_ID = "taLOzliUuwLurpOGJBxzzVuvzdeARJ"
    const val REDIRECT_URI = "https://tv.sfr.fr/"
    const val BACKEND = "https://ws-backendtv.sfr.fr"
    const val LICENSE_URL = "$BACKEND/asgard-drm-widevine/public/licence"
    const val EPG_URL = "https://static-cdn.tv.sfr.net/data/epg/gen8/guide_web_%s.json"

    /** Browser-like UA: the backend serves the "browser" device profile. */
    val USER_AGENT = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    val COMMON_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Origin" to "https://tv.sfr.fr",
        "Referer" to "https://tv.sfr.fr/",
    )

    fun authorizeUrl(): String {
        val state = (1..32).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        return "https://www.sfr.fr/cas/oidc/authorize?client_id=$CLIENT_ID&scope=openid" +
            "&response_type=token&redirect_uri=${enc(REDIRECT_URI)}&state=$state"
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

class AuthExpiredException : IOException("SFR token expired")

data class Channel(
    val serviceId: String,
    val number: Int,
    val name: String,
    val logo: String?,
    val epgId: String?,
    val streamUrl: String,
    val timeShift: Boolean = false,      // pause / rewind allowed
    val startOver: Boolean = false,      // "restart from the beginning" allowed
    val affTimeShift: Boolean = false,   // anti fast-forward while time-shifted
    val affStartOver: Boolean = false,   // anti fast-forward in start-over
)

object SfrApi {

    private fun http(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        (Sfr.COMMON_HEADERS + headers).forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        if (code == 401 || code == 403) throw AuthExpiredException()
        if (code !in 200..299) throw IOException("HTTP $code on ${url.substringBefore('?')}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Channels included in the subscription, sorted by channel number. */
    fun channels(token: String): List<Channel> {
        val arr = JSONArray(
            http("${Sfr.BACKEND}/sekai-service-plan/public/v2/service-list?app=gen8&device=browser&token=${Sfr.enc(token)}")
        )
        val out = ArrayList<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!o.optBoolean("access") || !o.optBoolean("streamAvailable", true)) continue
            val stream = o.optJSONArray("streams")?.let { s ->
                (0 until s.length()).map { s.getJSONObject(it) }
                    .firstOrNull { it.optString("drm") == "WIDEVINE" && it.optString("format") == "dash" }
            } ?: continue
            val logo = o.optJSONArray("images")?.let { im ->
                (0 until im.length()).map { im.getJSONObject(it) }
                    .firstOrNull { it.optString("type") == "color" }?.optString("url")
            }
            out += Channel(
                serviceId = o.getString("serviceId"),
                number = o.optInt("zappingId", 9999),
                name = o.optString("name"),
                logo = logo,
                epgId = o.optString("epgId").ifEmpty { null },
                streamUrl = stream.getString("url"),
                timeShift = o.optBoolean("timeShift"),
                startOver = o.optBoolean("startOver"),
                affTimeShift = o.optJSONObject("aff")?.optBoolean("timeShift") ?: false,
                affStartOver = o.optJSONObject("aff")?.optBoolean("startOver") ?: false,
            )
        }
        return out.sortedBy { it.number }
    }

    /** Asks the backend to sign the stream URL (mkpc). Falls back to the raw URL, like the web player. */
    /** Same live stream, starting at [startMs] (wall clock) instead of the live edge — what the web "restart" API returns. */
    fun streamFrom(liveUrl: String, startMs: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return liveUrl.replace(Regex("start=[^&]*"), "start=" + f.format(java.util.Date(startMs)))
    }

    fun signStream(token: String, url: String, eme: Int): String = try {
        val body = JSONObject().put("url", url).put("issuer", "sfrtv_web").put("eme", eme).toString()
        JSONObject(
            http("${Sfr.BACKEND}/sekai-service-plan/public/v1/mkpc/jwt?token=${Sfr.enc(token)}", "POST", body)
        ).optString("url").ifEmpty { url }
    } catch (e: AuthExpiredException) {
        throw e
    } catch (e: Exception) {
        url
    }

    /** Contract id of the active internet line ("mainLine.contractId" in the web player), cached. */
    fun accountId(context: Context, token: String): String? {
        val prefs = context.getSharedPreferences("prefs", 0)
        prefs.getString("accountId", null)?.let { return it }
        val o = JSONObject(http("${Sfr.BACKEND}/heimdall-core/public/api/v3/userProfiles?token=${Sfr.enc(token)}"))
        val subs = o.optJSONArray("internetSubscriptions") ?: return null
        val id = (0 until subs.length()).map { subs.getJSONObject(it) }
            .firstOrNull { it.optString("status") == "ACTIVATED" }?.optString("siebelId")?.ifEmpty { null }
        if (id != null) prefs.edit().putString("accountId", id).apply()
        return id
    }

    /** Value of the "customdata" header of the Widevine licence request; type LIVEOTT (live) or RESTART (time-shift / start-over). */
    fun licenseCustomData(context: Context, token: String, type: String = "LIVEOTT"): String {
        val accountId = try { accountId(context, token) } catch (_: Exception) { null }
        val dm = context.resources.displayMetrics
        return linkedMapOf(
            "description" to Sfr.USER_AGENT,
            "deviceId" to "byPassARTHIUS",
            "deviceName" to "Chrome",
            "deviceType" to "PC",
            "screenType" to "PC",
            "osName" to "Android",
            "osVersion" to Build.VERSION.RELEASE,
            "persistent" to "false",
            "resolution" to "${dm.widthPixels}x${dm.heightPixels}",
            "tokenType" to "castoken",
            "enabledMkpc" to "true",
            "tokenSSO" to token,
            "type" to type,
            "accountId" to (accountId ?: ""),
        ).entries.joinToString("&") { "${it.key}=${it.value}" }
    }
}
