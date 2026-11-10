package com.arturtv.player

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

const val USER_AGENT = "ARTUR-TV/2.75 (Android)"

data class TvChannel(
    val name: String,
    val group: String,
    val url: String,
    val number: Int,
    val catchup: String? = null,        // тип архива: default / append / shift / flussonic
    val catchupSource: String? = null,  // шаблон ссылки архива
    val catchupDays: Int = 0            // глубина архива в днях (0 = не указана)
) {
    /** Ключ для избранного/недавних: не путает каналы с одинаковым названием. */
    val key: String get() = "$name|$url"
    val hasCatchup: Boolean
        get() = catchup?.lowercase() !in setOf<String?>("none", "off", "false", "0") &&
            (catchup != null || !catchupSource.isNullOrBlank())
}

data class TvPlaylist(
    val id: String,
    val name: String,
    val source: String,
    val channels: List<TvChannel>
)

private fun attrRegex(name: String) = Regex("(?:^|\\s)" + Regex.escape(name) + "=\"([^\"]*)\"")

private val RE_GROUP = attrRegex("group-title")
private val RE_CHNO = attrRegex("tvg-chno")
private val RE_CATCHUP = attrRegex("catchup")
private val RE_CATCHUP_TYPE = attrRegex("catchup-type")
private val RE_CATCHUP_SRC = attrRegex("catchup-source")
private val RE_CATCHUP_DAYS = attrRegex("catchup-days")
private val RE_TVG_REC = attrRegex("tvg-rec")

private fun Regex.value(line: String): String? =
    find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

/** Keep explicit "none" values: they must override playlist-wide catch-up defaults. */
private fun normalizeCatchup(value: String?): String? {
    val v = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return if (v in setOf("none", "off", "false", "0")) "none" else v
}

/** Название — всё после первой запятой вне кавычек (запятые внутри атрибутов не мешают). */
private fun extinfName(line: String): String {
    var inQuote = false
    for (i in line.indices) {
        val c = line[i]
        if (c == '"') inQuote = !inQuote
        else if (c == ',' && !inQuote) return line.substring(i + 1).trim()
    }
    return ""
}

fun parseM3uPlaylist(raw: String): List<TvChannel> {
    val result = ArrayList<TvChannel>()

    // Значения по умолчанию из заголовка #EXTM3U
    var globalCatchup: String? = null
    var globalCatchupSrc: String? = null
    var globalDays = 0

    // #EXTGRP действует на все следующие каналы, пока не сменится
    var extGrp: String? = null

    // Данные текущей записи (сбрасываются после каждого канала)
    var name = ""
    var group: String? = null
    var number: Int? = null
    var catchup: String? = null
    var catchupSrc: String? = null
    var days = 0

    for (lineRaw in raw.lineSequence()) {
        val line = lineRaw.trim().removePrefix("\uFEFF")
        when {
            line.startsWith("#EXTM3U") -> {
                globalCatchup = normalizeCatchup(RE_CATCHUP.value(line) ?: RE_CATCHUP_TYPE.value(line))
                globalCatchupSrc = RE_CATCHUP_SRC.value(line)
                globalDays = (RE_CATCHUP_DAYS.value(line) ?: RE_TVG_REC.value(line))?.toIntOrNull() ?: 0
            }
            line.startsWith("#EXTGRP:") -> {
                extGrp = line.removePrefix("#EXTGRP:").trim().ifEmpty { null }
            }
            line.startsWith("#EXTINF:") -> {
                group = RE_GROUP.value(line)
                number = RE_CHNO.value(line)?.toIntOrNull()
                catchup = normalizeCatchup(RE_CATCHUP.value(line) ?: RE_CATCHUP_TYPE.value(line))
                catchupSrc = RE_CATCHUP_SRC.value(line)
                days = (RE_CATCHUP_DAYS.value(line) ?: RE_TVG_REC.value(line))?.toIntOrNull() ?: 0
                name = extinfName(line)
            }
            line.isNotEmpty() && !line.startsWith("#") -> {
                val idx = result.size + 1
                result.add(
                    TvChannel(
                        name = name.ifEmpty { "Канал $idx" },
                        group = group ?: extGrp ?: "Разное",
                        url = line,
                        number = number ?: idx,
                        catchup = catchup ?: globalCatchup,
                        catchupSource = catchupSrc ?: globalCatchupSrc,
                        catchupDays = if (days > 0) days else globalDays
                    )
                )
                name = ""
                group = null
                number = null
                catchup = null
                catchupSrc = null
                days = 0
            }
        }
    }
    return result
}

/** Скачивает текст по ссылке; бросает IOException с понятным текстом при HTTP-ошибке. */
suspend fun downloadText(client: OkHttpClient, url: String): String = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        resp.body?.string() ?: throw IOException("пустой ответ сервера")
    }
}
