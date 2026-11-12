package com.arturtv.player

import android.content.Context
import android.util.Xml
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser

data class EpgProgramme(val startMs: Long, val stopMs: Long, val title: String)

/** Для сопоставления названий каналов: нижний регистр, только буквы/цифры, без суффикса «hd». */
internal fun normChannelName(s: String): String =
    s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "").removeSuffix("hd")

/**
 * Программа передач из XMLTV (url-tvg в заголовке M3U). Поддерживает .xml и .xml.gz.
 * В памяти держатся только каналы активного плейлиста и окно от −6 ч до +36 ч.
 * Файл кэшируется на 6 часов; при ошибке сети используется старый кэш.
 */
class EpgStore(context: Context) {
    private val dir = File(context.applicationContext.cacheDir, "epg").apply { mkdirs() }

    @Volatile private var byId: Map<String, List<EpgProgramme>> = emptyMap()
    @Volatile private var nameToId: Map<String, String> = emptyMap()

    fun clear() {
        byId = emptyMap()
        nameToId = emptyMap()
    }

    /** Текущая и следующая передача канала (или null, если данных нет). */
    fun nowNext(ch: TvChannel, nowMs: Long): Pair<EpgProgramme?, EpgProgramme?> {
        val ids = byId
        val id = ch.tvgId?.lowercase()?.takeIf { it in ids } ?: nameToId[normChannelName(ch.name)]
        val list = id?.let { ids[it] } ?: return null to null
        val i = list.indexOfFirst { nowMs >= it.startMs && nowMs < it.stopMs }
        return if (i >= 0) list[i] to list.getOrNull(i + 1)
        else null to list.firstOrNull { it.startMs > nowMs }
    }

    suspend fun load(client: OkHttpClient, url: String, channels: List<TvChannel>) =
        withContext(Dispatchers.IO) {
            val file = File(dir, "epg_" + Integer.toHexString(url.hashCode()) + ".xml")
            val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > TTL_MS
            if (stale) {
                try {
                    download(client, url, file)
                } catch (e: IOException) {
                    if (!file.exists()) throw e
                }
            }
            parse(file, channels)
        }

    private fun download(client: OkHttpClient, url: String, target: File) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("пустой ответ")
            val tmp = File(dir, target.name + ".tmp")
            BufferedInputStream(body.byteStream()).use { raw ->
                raw.mark(2)
                val b0 = raw.read()
                val b1 = raw.read()
                raw.reset()
                val gz = b0 == 0x1f && b1 == 0x8b
                val src = if (gz) GZIPInputStream(raw) else raw
                tmp.outputStream().use { out -> src.copyTo(out) }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("не удалось сохранить программу передач")
            }
        }
    }

    private fun parse(file: File, channels: List<TvChannel>) {
        val wantedIds = channels.mapNotNull { it.tvgId?.lowercase()?.takeIf { s -> s.isNotBlank() } }.toHashSet()
        val wantedNames = channels.map { normChannelName(it.name) }.toHashSet()
        val idSet = HashSet<String>(wantedIds)
        val names = HashMap<String, String>()
        val progs = HashMap<String, MutableList<EpgProgramme>>()
        val now = System.currentTimeMillis()
        val from = now - 6 * HOUR
        val to = now + 36 * HOUR
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

        fun time(s: String?): Long? {
            val t = s?.trim() ?: return null
            val v = if (t.length > 14 && t.contains(' ')) t else "${t.take(14)} +0000"
            return try { fmt.parse(v)?.time } catch (_: ParseException) { null }
        }

        BufferedInputStream(FileInputStream(file)).use { ins ->
            val p = Xml.newPullParser()
            p.setInput(ins, null)
            var ev = p.eventType
            var chanId: String? = null
            var pId: String? = null
            var pStart = 0L
            var pStop = 0L
            var pTitle: String? = null
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> chanId = p.getAttributeValue(null, "id")?.lowercase()
                        "display-name" -> {
                            val id = chanId
                            val n = normChannelName(p.nextText())
                            if (id != null && n in wantedNames && n !in names) {
                                names[n] = id
                                idSet.add(id)
                            }
                        }
                        "programme" -> {
                            val id = p.getAttributeValue(null, "channel")?.lowercase()
                            val st = time(p.getAttributeValue(null, "start"))
                            val sp = time(p.getAttributeValue(null, "stop"))
                            if (id != null && id in idSet && st != null && sp != null) {
                                pId = id; pStart = st; pStop = sp; pTitle = null
                            } else {
                                pId = null
                            }
                        }
                        "title" -> if (pId != null && pTitle == null) pTitle = p.nextText().trim()
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "channel" -> chanId = null
                        "programme" -> {
                            val id = pId
                            val title = pTitle
                            if (id != null && !title.isNullOrEmpty() && pStop >= from && pStart <= to) {
                                progs.getOrPut(id) { ArrayList() }.add(EpgProgramme(pStart, pStop, title))
                            }
                            pId = null
                            pTitle = null
                        }
                    }
                }
                ev = p.next()
            }
        }
        val sorted = HashMap<String, List<EpgProgramme>>(progs.size)
        for ((k, v) in progs) sorted[k] = v.sortedBy { it.startMs }
        byId = sorted
        nameToId = names
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val TTL_MS = 6 * HOUR
    }
}
