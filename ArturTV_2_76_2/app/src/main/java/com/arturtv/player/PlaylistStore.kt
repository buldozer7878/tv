package com.arturtv.player

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ArturTV.Store"

/**
 * Хранение между запусками:
 *  - плейлисты (с каналами) — файл playlists.json во внутренней памяти приложения;
 *  - избранное, недавние, активный плейлист и последний канал — SharedPreferences.
 */
class PlaylistStore(context: Context) {
    private val appCtx = context.applicationContext
    private val file = File(appCtx.filesDir, "playlists.json")
    private val backup = File(appCtx.filesDir, "playlists.json.bak")
    private val tmpFile = File(appCtx.filesDir, "playlists.json.tmp")
    private val prefs = appCtx.getSharedPreferences("arturtv", Context.MODE_PRIVATE)

    private fun parseFile(f: File): List<TvPlaylist> {
        val arr = JSONArray(f.readText(Charsets.UTF_8))
        val out = ArrayList<TvPlaylist>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val chArr = o.getJSONArray("channels")
                val channels = ArrayList<TvChannel>(chArr.length())
                for (j in 0 until chArr.length()) {
                    val c = chArr.getJSONObject(j)
                    channels.add(
                        TvChannel(
                            name = c.getString("n"),
                            group = c.optString("g", "Разное"),
                            url = c.getString("u"),
                            number = c.optInt("o", j + 1),
                            catchup = c.optString("c", "").ifEmpty { null },
                            catchupSource = c.optString("s", "").ifEmpty { null },
                            catchupDays = c.optInt("d", 0)
                        )
                    )
                }
                if (channels.isNotEmpty()) {
                    out.add(TvPlaylist(o.getString("id"), o.getString("name"), o.optString("source", ""), channels))
                }
            } catch (e: Exception) {
                // Один испорченный плейлист не должен убивать остальные.
                Log.e(TAG, "Плейлист #$i в ${f.name} пропущен", e)
            }
        }
        return out
    }

    /** Читает основной файл; если он повреждён — берёт резервную копию (.bak). */
    fun loadPlaylists(): List<TvPlaylist> {
        for (f in listOf(file, backup)) {
            if (!f.exists()) continue
            try {
                return parseFile(f)
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось прочитать ${f.name}", e)
                if (f == file) {
                    // Сохраняем испорченный файл для разбора, чтобы следующее сохранение его не затёрло.
                    runCatching { f.copyTo(File(appCtx.filesDir, "playlists.json.corrupt"), overwrite = true) }
                }
            }
        }
        return emptyList()
    }

    private val saver = Executors.newSingleThreadExecutor()

    /** Сохранение в одной очереди: порядок записей совпадает с порядком вызовов. */
    fun savePlaylistsAsync(list: List<TvPlaylist>) {
        saver.execute { savePlaylists(list) }
    }

    @Synchronized
    fun savePlaylists(list: List<TvPlaylist>) {
        try {
            val arr = JSONArray()
            for (pl in list) {
                val chArr = JSONArray()
                for (c in pl.channels) {
                    val o = JSONObject()
                    o.put("n", c.name)
                    o.put("g", c.group)
                    o.put("u", c.url)
                    o.put("o", c.number)
                    c.catchup?.let { o.put("c", it) }
                    c.catchupSource?.let { o.put("s", it) }
                    if (c.catchupDays > 0) o.put("d", c.catchupDays)
                    chArr.put(o)
                }
                arr.put(
                    JSONObject()
                        .put("id", pl.id)
                        .put("name", pl.name)
                        .put("source", pl.source)
                        .put("channels", chArr)
                )
            }
            // 1) полностью пишем временный файл; 2) последнюю рабочую версию копируем в .bak;
            // 3) подменяем основной файл. Резервная копия остаётся и используется при загрузке.
            tmpFile.writeText(arr.toString(), Charsets.UTF_8)
            if (file.exists()) {
                runCatching { file.copyTo(backup, overwrite = true) }
            }
            if (!tmpFile.renameTo(file)) {
                file.delete()
                if (!tmpFile.renameTo(file)) {
                    if (!file.exists() && backup.exists()) {
                        runCatching { backup.copyTo(file, overwrite = true) }
                    }
                    throw java.io.IOException("Не удалось заменить файл плейлистов")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось сохранить плейлисты", e)
        }
    }

    var activeId: String?
        get() = prefs.getString("active", null)
        set(value) {
            prefs.edit().putString("active", value).apply()
        }

    var lastKey: String?
        get() = prefs.getString("last", null)
        set(value) {
            prefs.edit().putString("last", value).apply()
        }

    var favorites: Set<String>
        get() = prefs.getStringSet("favs", null)?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putStringSet("favs", HashSet(value)).apply()
        }

    var recent: List<String>
        get() = try {
            val arr = JSONArray(prefs.getString("recent", "[]"))
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
        set(value) {
            prefs.edit().putString("recent", JSONArray(value).toString()).apply()
        }
}
