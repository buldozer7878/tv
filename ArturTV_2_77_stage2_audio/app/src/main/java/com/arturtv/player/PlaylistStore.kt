package com.arturtv.player

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Хранение между запусками:
 *  - плейлисты (с каналами) — файл playlists.json во внутренней памяти приложения;
 *  - избранное, недавние, активный плейлист и последний канал — SharedPreferences.
 */
class PlaylistStore(context: Context) {
    private val appCtx = context.applicationContext
    private val file = File(appCtx.filesDir, "playlists.json")
    private val prefs = appCtx.getSharedPreferences("arturtv", Context.MODE_PRIVATE)

    fun loadPlaylists(): List<TvPlaylist> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(file.readText())
            val out = ArrayList<TvPlaylist>()
            for (i in 0 until arr.length()) {
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
            }
            out
        }
    } catch (_: Exception) {
        emptyList()
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
            // Сначала полностью записываем временный файл. Старый файл не удаляем,
            // пока новый не готов: при неудачном rename сохраняем возможность отката.
            val tmp = File(appCtx.filesDir, "playlists.json.tmp")
            val backup = File(appCtx.filesDir, "playlists.json.bak")
            tmp.writeText(arr.toString(), Charsets.UTF_8)

            if (!tmp.renameTo(file)) {
                var movedOldFile = false
                if (file.exists()) {
                    if (backup.exists()) backup.delete()
                    movedOldFile = file.renameTo(backup)
                    if (!movedOldFile) throw java.io.IOException("Не удалось сохранить предыдущий плейлист")
                }
                if (!tmp.renameTo(file)) {
                    if (movedOldFile && backup.exists()) backup.renameTo(file)
                    throw java.io.IOException("Не удалось заменить файл плейлистов")
                }
                if (backup.exists()) backup.delete()
            } else if (backup.exists()) {
                backup.delete()
            }
        } catch (_: Exception) {
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
