package fr.sylvain.matv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDrm
import android.util.JsonReader
import android.util.LruCache
import android.widget.ImageView
import androidx.media3.common.C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream

data class Program(val title: String, val genre: String?, val start: Long, val end: Long)

/** In-memory app state: channel list, EPG window, preferences. */
object Repo {
    @Volatile var channels: List<Channel> = emptyList()
    @Volatile private var epg: Map<String, List<Program>> = emptyMap()
    @Volatile private var epgLoadedAt = 0L

    suspend fun loadChannels(token: String) = withContext(Dispatchers.IO) {
        channels = SfrApi.channels(token)
    }

    // ---- EPG (public JSON, ~10 MB/day, streamed and trimmed to a few hours) ----

    suspend fun refreshEpgIfNeeded() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - epgLoadedAt < 60 * 60_000 && epg.isNotEmpty()) return@withContext
        val wanted = channels.mapNotNull { it.epgId }.toHashSet()
        val day = SimpleDateFormat("yyyyMMdd", Locale.FRANCE).format(Date(now))
        val map = HashMap<String, MutableList<Program>>()
        try {
            val c = URL(Sfr.EPG_URL.format(day)).openConnection() as HttpURLConnection
            c.setRequestProperty("Accept-Encoding", "gzip")
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            val input = if (c.contentEncoding == "gzip") GZIPInputStream(c.inputStream) else c.inputStream
            JsonReader(input.bufferedReader()).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "epg") { r.skipValue(); continue }
                    r.beginObject()
                    while (r.hasNext()) {
                        val id = r.nextName()
                        if (id !in wanted) { r.skipValue(); continue }
                        val list = map.getOrPut(id) { ArrayList() }
                        r.beginArray()
                        while (r.hasNext()) readProgram(r)?.let { p ->
                            if (p.end > now - 8 * 3600_000 && p.start < now + 8 * 3600_000) list += p
                        }
                        r.endArray()
                    }
                    r.endObject()
                }
                r.endObject()
            }
            epg = map.mapValues { e -> e.value.sortedBy { it.start } }
            epgLoadedAt = now
        } catch (_: Exception) {
            // EPG is a nice-to-have: ignore failures
        }
    }

    private fun readProgram(r: JsonReader): Program? {
        var title: String? = null; var genre: String? = null; var start = 0L; var end = 0L
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "title" -> title = r.nextString()
            "genre" -> genre = r.nextString()
            "startDate" -> start = r.nextLong()
            "endDate" -> end = r.nextLong()
            else -> r.skipValue()
        }
        r.endObject()
        return title?.let { Program(it, genre, start, end) }
    }

    fun nowNext(ch: Channel): Pair<Program?, Program?> {
        val list = ch.epgId?.let { epg[it] } ?: return null to null
        val now = System.currentTimeMillis()
        val i = list.indexOfFirst { it.start <= now && it.end > now }
        if (i < 0) return null to list.firstOrNull { it.start > now }
        return list[i] to list.getOrNull(i + 1)
    }

    /** Programme on air at wall-clock time [t] (EPG trimmed to a few hours around now). */
    fun programAt(ch: Channel, t: Long): Program? =
        ch.epgId?.let { epg[it] }?.firstOrNull { it.start <= t && it.end > t }

    // ---- preferences ----

    fun lastChannel(ctx: Context): String? = ctx.getSharedPreferences("prefs", 0).getString("last", null)
    fun setLastChannel(ctx: Context, id: String) =
        ctx.getSharedPreferences("prefs", 0).edit().putString("last", id).apply()

    /** Widevine robustness level for the mkpc call: 5 (HW_SECURE_ALL) on L1 devices like the Fire TV Stick 4K. */
    val emeLevel: Int by lazy {
        try {
            val drm = MediaDrm(C.WIDEVINE_UUID)
            val level = drm.getPropertyString("securityLevel")
            drm.close()
            if (level == "L1") 5 else 1
        } catch (_: Exception) { 1 }
    }

    fun time(ms: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(ms))
}

/** Tiny logo loader with memory cache. */
object Logos {
    private val cache = LruCache<String, Bitmap>(300)

    fun load(scope: CoroutineScope, url: String?, into: ImageView) {
        into.tag = url
        into.setImageDrawable(null)
        if (url == null) return
        cache.get(url)?.let { into.setImageBitmap(it); return }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                } catch (_: Exception) { null }
            }
            if (bmp != null) {
                cache.put(url, bmp)
                if (into.tag == url) into.setImageBitmap(bmp)
            }
        }
    }
}
