package com.arturtv.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import android.util.Log
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

const val DEMO_ID = "demo"

// Встроенный демо-плейлист: два реальных тестовых потока, чтобы сразу проверить плеер.
// Свои каналы добавляйте через «☰+ Плейлист» (ссылка, файл или текст M3U).
private val demoPlaylist = TvPlaylist(
    DEMO_ID, "Демо", "Встроенный",
    listOf(
        TvChannel("Тестовый поток Mux (HLS)", "Тест", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", 1),
        TvChannel(
            "Тестовый поток Apple (HLS)", "Тест",
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8", 2
        )
    )
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            requestedOrientation = if (isTv) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    ArturTvScreen()
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("ArturTV", "Fatal in onCreate", t)
            throw t
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
} catch (_: Exception) {
    null
}

private fun List<TvChannel>.firstOrNullSafe(): TvChannel? = if (isEmpty()) null else first()

@OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun ArturTvScreen() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTvDevice = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    val isPhonePortrait = !isTvDevice && configuration.screenWidthDp < 600 &&
        configuration.screenHeightDp > configuration.screenWidthDp
    val scope = rememberCoroutineScope()
    val store = remember { PlaylistStore(context) }
    val httpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ---------- состояние ----------
    var userPlaylists by remember { mutableStateOf(store.loadPlaylists()) }
    val playlists = remember(userPlaylists) { listOf(demoPlaylist) + userPlaylists }
    var activePlaylistId by remember {
        mutableStateOf(
            store.activeId?.takeIf { id -> id == DEMO_ID || userPlaylists.any { it.id == id } }
                ?: userPlaylists.firstOrNull()?.id
                ?: DEMO_ID
        )
    }
    val activePlaylist = playlists.find { it.id == activePlaylistId } ?: playlists.first()

    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Все") }
    var selectedGroup by remember { mutableStateOf("Все") }
    var favorites by remember { mutableStateOf(store.favorites) }
    var recent by remember { mutableStateOf(store.recent) }

    var currentChannel by remember {
        val channels = activePlaylist.channels
        val initial = channels.firstOrNull { it.key == store.lastKey }
            ?: channels.firstOrNullSafe()
        mutableStateOf(initial)
    }
    var shiftSec by remember { mutableStateOf(0L) }
    var isFullscreen by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    var showPlaylistModal by remember { mutableStateOf(false) }
    var showArchiveModal by remember { mutableStateOf(false) }
    var showSettingsModal by remember { mutableStateOf(false) }
    var dialogStatus by remember { mutableStateOf<String?>(null) }
    var dialogLoading by remember { mutableStateOf(false) }

    var playerError by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }

    // Набор номера канала цифрами 0-9 с пульта ТВ
    var searchFocused by remember { mutableStateOf(false) }
    var numpadDigits by remember { mutableStateOf("") }
    var numpadJob by remember { mutableStateOf<Job?>(null) }

    val exoPlayer = remember {
        try {
            buildPlayer(context)
        } catch (t: Throwable) {
            // Последний рубеж: если даже fallback в buildPlayer упал
            androidx.media3.exoplayer.ExoPlayer.Builder(context.applicationContext).build().apply { playWhenReady = true }
        }
    }
    val retry = remember { intArrayOf(0) }
    var playbackGeneration by remember { mutableStateOf(0) }
    var retryJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    val fsListState = rememberLazyListState()
    val fsGroupListState = rememberLazyListState()
    var focusedChannelKey by remember { mutableStateOf<String?>(null) }
    // Курсор (фокус пульта) по категориям — отдельно от выбранной категории
    var focusedCategory by remember { mutableStateOf<String?>(null) }

    // Полноэкранное меню: 0 = скрыто, 1 = список каналов, 2 = категории
    // Важно: смена fsPanel НЕ должна сбрасывать isFullscreen
    var fsPanel by remember { mutableStateOf(0) }
    var cursorIndex by remember { mutableStateOf(0) }
    var groupCursor by remember { mutableStateOf(0) }

    // Нижняя плашка fullscreen — автоскрытие
    var showFsBar by remember { mutableStateOf(true) }
    var fsBarTick by remember { mutableStateOf(0) }

    // Двойной клик по каналу → fullscreen
    var lastClickKey by remember { mutableStateOf<String?>(null) }
    var lastClickTime by remember { mutableStateOf(0L) }

    // Back: сначала закрываем боковое меню, потом выходим из fullscreen
    BackHandler(enabled = isFullscreen) {
        if (fsPanel > 0) fsPanel = 0 else isFullscreen = false
    }

    // На телефоне интерфейс стартует вертикально, а полноэкранное видео открывается горизонтально.
    LaunchedEffect(isFullscreen, isTvDevice) {
        (context as? Activity)?.requestedOrientation = when {
            isTvDevice -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            isFullscreen -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // ---------- действия ----------
    fun selectChannel(ch: TvChannel?) {
        if (ch == null) return
        currentChannel = ch
        shiftSec = 0L
        focusedChannelKey = ch.key
    }

    fun selectFullscreenChannel(ch: TvChannel?) {
        if (ch == null) return
        selectChannel(ch)
        // Выбор канала в боковом списке не должен закрывать полноэкранный режим.
        isFullscreen = true
        fsPanel = 0
        showFsBar = true
        fsBarTick++
    }

    fun openPlaylistDialog() {
        dialogStatus = null
        showPlaylistModal = true
    }

    fun openPlaylist(pl: TvPlaylist, closeDialog: Boolean = true) {
        if (pl.channels.isEmpty()) {
            dialogStatus = "Плейлист пустой — каналов нет"
            return
        }
        activePlaylistId = pl.id
        selectedGroup = "Все"
        selectedTab = "Все"
        searchQuery = ""
        val last = store.lastKey
        val restore = pl.channels.firstOrNull { it.key == last } ?: pl.channels.firstOrNullSafe()
        selectChannel(restore)
        store.activeId = pl.id
        if (closeDialog) showPlaylistModal = false
    }

    fun addPlaylist(pl: TvPlaylist) {
        if (pl.channels.isEmpty()) return
        val updated = userPlaylists + pl
        userPlaylists = updated
        store.savePlaylistsAsync(updated)
        openPlaylist(pl)
    }

    fun deletePlaylist(pl: TvPlaylist) {
        val updated = userPlaylists.filter { it.id != pl.id }
        userPlaylists = updated
        store.savePlaylistsAsync(updated)
        if (activePlaylistId == pl.id) openPlaylist(demoPlaylist, closeDialog = false)
    }

    fun loadFromUrl(nameInput: String, linkInput: String) {
        val link = linkInput.trim()
        if (!link.startsWith("http://", ignoreCase = true) && !link.startsWith("https://", ignoreCase = true)) {
            dialogStatus = "Ссылка должна начинаться с http:// или https://"
            return
        }
        dialogLoading = true
        dialogStatus = null
        scope.launch {
            try {
                val body = downloadText(httpClient, link)
                val parsed = withContext(Dispatchers.Default) { parseM3uPlaylist(body) }
                if (parsed.isEmpty()) {
                    dialogStatus = "По ссылке не найдено ни одного канала — проверьте формат M3U"
                } else {
                    val cleanName = nameInput.trim().ifBlank {
                        link.substringAfterLast('/').substringBefore('?').removeSuffix(".m3u8").removeSuffix(".m3u").ifBlank { "URL плейлист" }
                    }
                    addPlaylist(
                        TvPlaylist("url_${System.currentTimeMillis()}", cleanName, link, parsed, parseM3uEpgUrl(body))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dialogStatus = "Ошибка загрузки: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                dialogLoading = false
            }
        }
    }

    // Выбор одного или нескольких локальных файлов .m3u / .m3u8 с флешки или памяти ТВ
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            dialogLoading = true
            dialogStatus = null
            scope.launch {
                try {
                    var addedCount = 0
                    for (uri in uris) {
                        val (text, name) = withContext(Dispatchers.IO) {
                            val t = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                            val rawName = displayName(context, uri) ?: uri.lastPathSegment ?: "Файл M3U"
                            t to rawName.replace(Regex("\\.(m3u8|m3u|txt)$", RegexOption.IGNORE_CASE), "")
                        }
                        val parsed = withContext(Dispatchers.Default) { parseM3uPlaylist(text) }
                        if (parsed.isNotEmpty()) {
                            addPlaylist(TvPlaylist("file_${System.currentTimeMillis()}_$addedCount", name, "Локальный файл", parsed, parseM3uEpgUrl(text)))
                            addedCount++
                        }
                    }
                    if (addedCount == 0) {
                        dialogStatus = "В выбранном файле не найдено каналов M3U"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dialogStatus = "Не удалось прочитать файл: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    dialogLoading = false
                }
            }
        }
    }

    // ---------- плеер ----------
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                // Диагностика звука: по Logcat можно увидеть, какой аудиокодек
                // отдаёт проблемный канал и поддерживает ли его устройство.
                tracks.groups.forEach { group ->
                    if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                        for (i in 0 until group.length) {
                            val format = group.mediaTrackGroup.getFormat(i)
                            Log.i(
                                "ARTUR_TV_AUDIO",
                                "mime=${format.sampleMimeType}; " +
                                    "codecs=${format.codecs}; lang=${format.language}; " +
                                    "channels=${format.channelCount}; sampleRate=${format.sampleRate}; " +
                                    "supported=${group.isTrackSupported(i)}; selected=${group.isTrackSelected(i)}"
                            )
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    playerError = null
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                // Эфир «убежал» вперёд (долгая пауза, лаг сети): просто перейти к текущему эфиру.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    return
                }
                val failedGeneration = playbackGeneration
                val failedChannelKey = currentChannel?.key
                val failedUrl = currentChannel?.let { buildPlayUrl(it, shiftSec) }
                if (retry[0] < 3 && failedUrl != null && failedChannelKey != null) {
                    retry[0]++
                    val attempt = retry[0]
                    playerError = "Подключаем эфир… (повтор $attempt/3)"
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        delay(2000L * attempt)
                        // Не перезапускаем предыдущий канал, если пользователь уже выбрал другой.
                        val selectedChannel = currentChannel
                        if (playbackGeneration == failedGeneration &&
                            selectedChannel?.key == failedChannelKey &&
                            selectedChannel?.let { buildPlayUrl(it, shiftSec) } == failedUrl) {
                            exoPlayer.setMediaItem(buildMediaItem(failedUrl))
                            exoPlayer.prepare()
                            exoPlayer.play()
                        }
                    }
                } else {
                    playerError = "Поток временно недоступен (${error.errorCodeName})"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            retryJob?.cancel()
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        var resume = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resume = exoPlayer.playWhenReady
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_START -> if (resume) {
                    if (exoPlayer.isCurrentMediaItemLive) exoPlayer.seekToDefaultPosition()
                    exoPlayer.play()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Отмена таймера набора номера канала при уходе с экрана
    DisposableEffect(Unit) {
        onDispose { numpadJob?.cancel() }
    }

    val playUrl = remember(currentChannel, shiftSec) {
        currentChannel?.let { buildPlayUrl(it, shiftSec) } ?: ""
    }
    LaunchedEffect(playUrl) {
        playbackGeneration++
        retryJob?.cancel()
        retryJob = null
        retry[0] = 0
        playerError = null
        if (playUrl.isBlank()) return@LaunchedEffect
        exoPlayer.setMediaItem(buildMediaItem(playUrl))
        exoPlayer.prepare()
        exoPlayer.play()
    }

    // Сторожок: поток завис в буферизации дольше 15 с — переподключаем (до 3 раз на канал).
    var stallTick by remember { mutableStateOf(0) }
    LaunchedEffect(isBuffering, playbackGeneration, stallTick) {
        if (!isBuffering || playUrl.isBlank()) return@LaunchedEffect
        delay(15_000L)
        if (exoPlayer.playbackState == Player.STATE_BUFFERING && retry[0] < 3) {
            retry[0]++
            playerError = "Нет данных от потока — переподключаем… (${retry[0]}/3)"
            exoPlayer.setMediaItem(buildMediaItem(playUrl))
            exoPlayer.prepare()
            exoPlayer.play()
            stallTick++
        }
    }

    // ---------- программа передач (XMLTV из url-tvg плейлиста) ----------
    val epg = remember { EpgStore(context) }
    var epgVersion by remember { mutableStateOf(0) }
    var epgNowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    fun epgNowTitle(ch: TvChannel?): String? {
        if (ch == null) return null
        if (epgVersion < 0) return null // чтение состояния: перерисовка после загрузки EPG
        return epg.nowNext(ch, epgNowMs).first?.title
    }
    LaunchedEffect(activePlaylist.id, activePlaylist.epgUrl) {
        val url = activePlaylist.epgUrl
        if (url.isBlank()) {
            epg.clear()
            epgVersion++
            return@LaunchedEffect
        }
        try {
            epg.load(httpClient, url, activePlaylist.channels)
            epgVersion++
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("ArturTV", "EPG load failed: ${t.message}")
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            epgNowMs = System.currentTimeMillis()
            delay(60_000L)
        }
    }

    // Автообновление плейлистов по ссылке при запуске: токены и адреса в ссылках устаревают.
    // Избранное, недавние и последний канал переносятся на новые адреса (по имени и группе).
    LaunchedEffect(Unit) {
        val targets = userPlaylists.filter {
            it.source.startsWith("http://", ignoreCase = true) || it.source.startsWith("https://", ignoreCase = true)
        }
        for (pl in targets) {
            try {
                val body = downloadText(httpClient, pl.source)
                val parsed = withContext(Dispatchers.Default) { parseM3uPlaylist(body) }
                if (parsed.isEmpty()) continue
                val newEpg = parseM3uEpgUrl(body)
                val newByName = parsed.associateBy { it.name + "|" + it.group }
                val remap = HashMap<String, String>()
                for (old in pl.channels) {
                    val nw = newByName[old.name + "|" + old.group] ?: continue
                    if (nw.key != old.key) remap[old.key] = nw.key
                }
                if (remap.isNotEmpty()) {
                    favorites = favorites.map { remap[it] ?: it }.toSet()
                    store.favorites = favorites
                    recent = recent.map { remap[it] ?: it }
                    store.recent = recent
                    store.lastKey?.let { k -> remap[k]?.let { store.lastKey = it } }
                }
                val refreshed = pl.copy(channels = parsed, epgUrl = newEpg.ifBlank { pl.epgUrl })
                userPlaylists = userPlaylists.map { if (it.id == pl.id) refreshed else it }
                store.savePlaylistsAsync(userPlaylists)
                if (activePlaylistId == pl.id) {
                    val cur = currentChannel
                    if (cur != null) {
                        val updated = parsed.firstOrNull { it.key == (remap[cur.key] ?: cur.key) }
                        if (updated != null && updated != cur) currentChannel = updated
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // нет сети — остаётся сохранённая копия плейлиста
            }
        }
    }

    LaunchedEffect(currentChannel) {
        val ch = currentChannel ?: return@LaunchedEffect
        val k = ch.key
        recent = (listOf(k) + recent.filter { it != k }).take(30)
        store.recent = recent
        store.lastKey = k
    }

    // ---------- списки ----------
    val groups = remember(activePlaylist) {
        listOf("Все") + activePlaylist.channels.map { it.group }.distinct()
    }

    val filteredChannels = remember(activePlaylist, searchQuery, selectedTab, selectedGroup, favorites, recent) {
        val base = activePlaylist.channels.filter { ch ->
            (selectedGroup == "Все" || ch.group == selectedGroup) &&
                (selectedTab != "Избранное" || favorites.contains(ch.key)) &&
                (selectedTab != "Недавние" || recent.contains(ch.key)) &&
                (searchQuery.isBlank() || ch.name.contains(searchQuery, ignoreCase = true))
        }
        if (selectedTab == "Недавние") base.sortedBy { recent.indexOf(it.key) } else base
    }

    fun stepChannel(delta: Int) {
        if (filteredChannels.isEmpty()) return
        val currentKey = currentChannel?.key
        val i = filteredChannels.indexOfFirst { it.key == currentKey }
        val next = when {
            i < 0 -> if (delta > 0) 0 else filteredChannels.lastIndex
            else -> (i + delta).mod(filteredChannels.size)
        }
        selectChannel(filteredChannels[next])
        scope.launch {
            listState.animateScrollToItem(next)
        }
    }

    fun syncCursorToCurrent() {
        val key = currentChannel?.key
        val i = filteredChannels.indexOfFirst { it.key == key }
        cursorIndex = if (i >= 0) i else 0
        val gi = groups.indexOf(selectedGroup)
        groupCursor = if (gi >= 0) gi else 0
    }

    // Курсор не выходит за границы при смене фильтра/группы
    LaunchedEffect(filteredChannels.size, selectedGroup) {
        if (filteredChannels.isEmpty()) {
            cursorIndex = 0
        } else if (cursorIndex >= filteredChannels.size) {
            cursorIndex = filteredChannels.lastIndex
        }
    }

    // Автоскрытие нижней плашки через 2.5 сек
    LaunchedEffect(isFullscreen, fsBarTick, currentChannel?.key) {
        if (!isFullscreen) return@LaunchedEffect
        showFsBar = true
        delay(2500L)
        if (fsPanel == 0) showFsBar = false
    }

    /** Скролл списка только если курсор ушёл за видимую область — иначе курсор бегает по экрану. */
    fun ensureChannelVisible(index: Int) {
        scope.launch {
            val info = fsListState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) {
                fsListState.scrollToItem(index)
                return@launch
            }
            val first = visible.first().index
            val last = visible.last().index
            when {
                index < first -> fsListState.scrollToItem(index)
                index > last -> fsListState.scrollToItem(index)
                // в пределах экрана — не трогаем скролл, двигается только подсветка
            }
        }
    }

    fun ensureGroupVisible(index: Int) {
        scope.launch {
            val info = fsGroupListState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) {
                fsGroupListState.scrollToItem(index)
                return@launch
            }
            val first = visible.first().index
            val last = visible.last().index
            when {
                index < first -> fsGroupListState.scrollToItem(index)
                index > last -> fsGroupListState.scrollToItem(index)
            }
        }
    }

    fun bumpFsBar() {
        showFsBar = true
        fsBarTick++
    }

    // Умная обработка клавиш пульта ТВ
    val previewKeyHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        val nk = event.nativeKeyEvent
        if (nk.action == KeyEvent.ACTION_DOWN && !showPlaylistModal && !showArchiveModal && !showSettingsModal) {
            val code = nk.keyCode
            when {
                code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP -> {
                    stepChannel(-1)
                    true
                }
                code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN -> {
                    stepChannel(1)
                    true
                }
                // --- Полноэкран: LEFT открывает меню (1=каналы, 2=категории).
                // isFullscreen не трогаем — панель поверх видео.
                isFullscreen && code == KeyEvent.KEYCODE_DPAD_LEFT -> {
                    bumpFsBar()
                    when (fsPanel) {
                        0 -> {
                            syncCursorToCurrent()
                            fsPanel = 1
                            ensureChannelVisible(cursorIndex)
                        }
                        1 -> {
                            val gi = groups.indexOf(selectedGroup)
                            groupCursor = if (gi >= 0) gi else 0
                            focusedCategory = groups.getOrNull(groupCursor)
                            fsPanel = 2
                            ensureGroupVisible(groupCursor)
                        }
                        else -> { /* уже на категориях */ }
                    }
                    true
                }
                // RIGHT только закрывает панель, не весь fullscreen
                isFullscreen && code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (fsPanel > 0) {
                        fsPanel -= 1
                        bumpFsBar()
                    }
                    true
                }
                isFullscreen && code == KeyEvent.KEYCODE_DPAD_UP -> {
                    bumpFsBar()
                    when (fsPanel) {
                        1 -> {
                            if (filteredChannels.isNotEmpty()) {
                                cursorIndex = (cursorIndex - 1).mod(filteredChannels.size)
                                ensureChannelVisible(cursorIndex)
                            }
                        }
                        2 -> {
                            if (groups.isNotEmpty()) {
                                groupCursor = (groupCursor - 1).mod(groups.size)
                                ensureGroupVisible(groupCursor)
                            }
                        }
                        else -> stepChannel(-1)
                    }
                    true
                }
                isFullscreen && code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    bumpFsBar()
                    when (fsPanel) {
                        1 -> {
                            if (filteredChannels.isNotEmpty()) {
                                cursorIndex = (cursorIndex + 1).mod(filteredChannels.size)
                                ensureChannelVisible(cursorIndex)
                            }
                        }
                        2 -> {
                            if (groups.isNotEmpty()) {
                                groupCursor = (groupCursor + 1).mod(groups.size)
                                ensureGroupVisible(groupCursor)
                            }
                        }
                        else -> stepChannel(1)
                    }
                    true
                }
                isFullscreen && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) -> {
                    when (fsPanel) {
                        1 -> {
                            // OK подтверждает канал; fullscreen остаётся, панель закрываем
                            filteredChannels.getOrNull(cursorIndex)?.let { selectFullscreenChannel(it) }
                            fsPanel = 0
                        }
                        2 -> {
                            // OK выбирает категорию → список каналов этой категории (всё ещё fullscreen)
                            groups.getOrNull(groupCursor)?.let {
                                selectedGroup = it
                                focusedCategory = it
                            }
                            cursorIndex = 0
                            fsPanel = 1
                            scope.launch { fsListState.scrollToItem(0) }
                        }
                        else -> {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        }
                    }
                    true
                }
                code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    true
                }
                code == KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    exoPlayer.play()
                    true
                }
                code == KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    exoPlayer.pause()
                    true
                }
                code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_BOOKMARK -> {
                    openPlaylistDialog()
                    true
                }
                !searchFocused && code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    val digit = (code - KeyEvent.KEYCODE_0).toString()
                    numpadDigits = (numpadDigits + digit).takeLast(4)
                    numpadJob?.cancel()
                    numpadJob = scope.launch {
                        delay(1100L)
                        val target = numpadDigits.toIntOrNull()
                        numpadDigits = ""
                        if (target != null) {
                            val match = filteredChannels.firstOrNull { it.number == target }
                                ?: filteredChannels.getOrNull(target - 1)
                            if (match != null) selectChannel(match)
                        }
                    }
                    true
                }
                else -> false
            }
        } else false
    }

    // ---------- интерфейс 1-в-1 ARTUR-TV ----------
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050714))
            .onPreviewKeyEvent(previewKeyHandler)
    ) {
        // Космический фон с туманностью и планетой снизу как на фото
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF060919), Color(0xFF151230), Color(0xFF23133D), Color(0xFF070B19)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
            )
            // Фиолетово-розовая туманность по центру/справа
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x55C084FC), Color(0x229333EA), Color.Transparent),
                    center = Offset(size.width * 0.65f, size.height * 0.38f),
                    radius = size.maxDimension * 0.45f
                ),
                center = Offset(size.width * 0.65f, size.height * 0.38f),
                radius = size.maxDimension * 0.45f
            )
            // Голубое свечение планеты снизу
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFBAE6FD), Color(0xFF38BDF8), Color(0xFF1E3A8A), Color.Transparent),
                    center = Offset(size.width * 0.56f, size.height * 1.32f),
                    radius = size.maxDimension * 0.55f
                ),
                center = Offset(size.width * 0.56f, size.height * 1.32f),
                radius = size.maxDimension * 0.55f
            )
        }

        // Компактный сенсорный интерфейс телефона в портретной ориентации.
        if (isPhonePortrait && !isFullscreen) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                                .background(Color(0xCC0C142C)).border(1.dp, Color(0xAA7DD3FC), RoundedCornerShape(11.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text("▶", color = Color(0xFF7DD3FC), fontSize = 17.sp) }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("ARTUR-TV", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            Text("${activePlaylist.channels.size} каналов", color = Color(0xFFCBD5E1), fontSize = 10.sp)
                        }
                    }
                    Text(
                        activePlaylist.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { openPlaylistDialog() }.padding(6.dp)
                    )
                }

                // Видео сохраняет пропорции 16:9 и не теряет место из-за боковой панели.
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp)).background(Color.Black)
                        .border(1.dp, Color(0x887DD3FC), RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply {
                            useController = false
                            this.resizeMode = resizeMode
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            setKeepContentOnPlayerReset(true)
                            player = exoPlayer
                        } },
                        update = { it.player = exoPlayer; it.resizeMode = resizeMode },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize().clickable { isFullscreen = true }
                    )
                    if (isBuffering && playerError == null) {
                        Text("Подключаем эфир...", color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center))
                    }
                    playerError?.let { msg ->
                        Text(msg, color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.Center).padding(8.dp))
                    }
                    TextButton(
                        onClick = { isFullscreen = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    ) { Text("⛶", color = Color.White, fontSize = 20.sp) }
                }

                Text(
                    currentChannel?.name ?: "Канал не выбран",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                epgNowTitle(currentChannel)?.let {
                    Text(
                        "Сейчас: $it",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                        modifier = Modifier.weight(1f)) { Text(if (exoPlayer.isPlaying) "Ⅱ" else "▶") }
                    OutlinedButton(onClick = { showArchiveModal = true }, modifier = Modifier.weight(1f)) { Text("Архив") }
                    OutlinedButton(
                        onClick = {
                            currentChannel?.key?.let { key ->
                                favorites = if (key in favorites) favorites - key else favorites + key
                                store.favorites = favorites
                            }
                        },
                        enabled = currentChannel != null,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (currentChannel?.key?.let { it in favorites } == true) "★" else "☆") }
                    OutlinedButton(onClick = { showSettingsModal = true }, modifier = Modifier.weight(1f)) { Text("⚙") }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("🔍 Найти канал", fontSize = 14.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused }
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("Все", "Избранное", "Недавние")) { tab ->
                        FilterChip(selected = selectedTab == tab, onClick = { selectedTab = tab },
                            label = { Text(tab, fontSize = 11.sp) })
                    }
                }
                Text("КАТЕГОРИИ", color = Color(0xFF7DD3FC), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 5.dp, bottom = 3.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(groups) { grp ->
                        val active = selectedGroup == grp
                        val focused = focusedCategory == grp
                        FilterChip(
                            selected = active,
                            onClick = {
                                selectedGroup = grp
                                focusedCategory = grp
                            },
                            label = {
                                Text(
                                    when {
                                        focused -> "▶ $grp"
                                        active -> "✓ $grp"
                                        else -> grp
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = if (active || focused) FontWeight.ExtraBold else FontWeight.Medium
                                )
                            },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                containerColor = Color(0x66202B45),
                                labelColor = Color(0xFFCBD5E1),
                                selectedContainerColor = Color(0xFF0EA5E9),
                                selectedLabelColor = Color.White
                            ),
                            border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = active,
                                borderColor = if (focused) Color.White else Color(0x447DD3FC),
                                selectedBorderColor = if (focused) Color.White else Color(0xFF38BDF8),
                                borderWidth = if (focused) 2.5.dp else 1.dp,
                                selectedBorderWidth = if (focused) 2.5.dp else 1.5.dp
                            ),
                            modifier = Modifier.onFocusChanged { st ->
                                if (st.isFocused) focusedCategory = grp
                            }
                        )
                    }
                }
                Text("КАНАЛЫ  ·  ${filteredChannels.size}", color = Color(0xFF7DD3FC),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .clip(RoundedCornerShape(14.dp)).background(Color(0xB30A1024))
                        .border(1.dp, Color(0x557DD3FC), RoundedCornerShape(14.dp)).padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(filteredChannels) { ch ->
                        val isSelected = ch.key == currentChannel?.key
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                                .background(if (isSelected) Color(0x5538BDF8) else Color.Transparent)
                                .clickable { selectChannel(ch) }
                                .padding(horizontal = 11.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${ch.number}. ${ch.name}", color = Color.White, fontSize = 14.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                epgNowTitle(ch)?.let {
                                    Text("▸ $it", color = Color(0xFF94A3B8), fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (favorites.contains(ch.key)) Text("★", color = Color(0xFF38BDF8))
                            if (isSelected) Text("  ▶", color = Color(0xFF7DD3FC), fontSize = 12.sp)
                        }
                    }
                }
            }
        // Полноэкранный режим видео
        } else if (isFullscreen) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            this.resizeMode = resizeMode
                            // Не забирать фокус у Compose — иначе пульт «теряет» курсор
                            isFocusable = false
                            isFocusableInTouchMode = false
                            runCatching {
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                setKeepContentOnPlayerReset(true)
                            }
                            player = exoPlayer
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = {
                        it.player = exoPlayer
                        it.resizeMode = resizeMode
                    },
                    onRelease = { it.player = null },
                    // Только касание: DPAD/пульт не должен закрывать fullscreen через clickable
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(fsPanel) {
                            detectTapGestures {
                                if (fsPanel > 0) fsPanel = 0
                                else isFullscreen = false
                            }
                        }
                )

                if (isBuffering && playerError == null) {
                    Text(
                        "Подключаем эфир...",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // Левая панель: список каналов (1) или категории (2).
                // Панель поверх видео, тапы по ней не закрывают fullscreen.
                if (fsPanel == 1 || fsPanel == 2) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .width(360.dp)
                            .background(Color(0xF50A1126))
                            .border(2.dp, Color(0xFF38BDF8))
                            .pointerInput(Unit) { detectTapGestures { /* тапы по панели не закрывают fullscreen */ } }
                            .padding(12.dp)
                    ) {
                        if (fsPanel == 2) {
                            Text("КАТЕГОРИИ", color = Color(0xFF7DD3FC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("↑↓ курсор · OK открыть · → к каналам", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(
                                state = fsGroupListState,
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                items(groups.size) { i ->
                                    val grp = groups[i]
                                    val on = i == groupCursor
                                    val isActive = grp == selectedGroup
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                when {
                                                    on -> Color(0xFF0EA5E9)
                                                    isActive -> Color(0x5538BDF8)
                                                    else -> Color(0x22FFFFFF)
                                                }
                                            )
                                            .border(
                                                width = if (on) 3.dp else if (isActive) 1.5.dp else 1.dp,
                                                color = when {
                                                    on -> Color.White
                                                    isActive -> Color(0xFF7DD3FC)
                                                    else -> Color(0x33FFFFFF)
                                                },
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                selectedGroup = grp
                                                groupCursor = i
                                                focusedCategory = grp
                                                cursorIndex = 0
                                                fsPanel = 1 // остаёмся в fullscreen, только список каналов
                                            }
                                            .padding(horizontal = 14.dp, vertical = 14.dp)
                                    ) {
                                        Text(
                                            when {
                                                on -> "▶ "
                                                isActive -> "● "
                                                else -> "   "
                                            } + grp,
                                            color = Color.White,
                                            fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Normal,
                                            fontSize = if (on) 17.sp else 14.sp
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                if (selectedGroup == "Все") "КАНАЛЫ" else selectedGroup.uppercase(),
                                color = Color(0xFF7DD3FC),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("↑↓ курсор · OK смотреть · ← категории · → закрыть панель", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(
                                state = fsListState,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(filteredChannels.size, key = { filteredChannels[it].key }) { i ->
                                    val ch = filteredChannels[i]
                                    val on = i == cursorIndex
                                    val playing = ch.key == currentChannel?.key
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                when {
                                                    on -> Color(0xFF0EA5E9)
                                                    playing -> Color(0x4438BDF8)
                                                    else -> Color(0x18FFFFFF)
                                                }
                                            )
                                            .border(
                                                width = if (on) 3.dp else if (playing) 1.5.dp else 1.dp,
                                                color = when {
                                                    on -> Color.White
                                                    playing -> Color(0xFF7DD3FC)
                                                    else -> Color(0x22FFFFFF)
                                                },
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                cursorIndex = i
                                                // Выбор канала в панели НЕ закрывает fullscreen
                                                selectFullscreenChannel(ch)
                                            }
                                            .padding(horizontal = 14.dp, vertical = 11.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            (if (on) "▶ " else if (playing) "● " else "   ") +
                                                "${ch.number}. ${ch.name}",
                                            color = Color.White,
                                            fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Normal,
                                            fontSize = if (on) 16.sp else 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (playing && !on) Text("эфир", color = Color(0xFF7DD3FC), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Мини-панель внизу — пропадает через ~2.5 сек
                if (showFsBar || fsPanel > 0) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(20.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xBB0A1126))
                            .border(1.dp, Color(0x8838BDF8), RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "${currentChannel?.number ?: "—"}. ${currentChannel?.name ?: ""}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        OutlinedButton(onClick = { fsPanel = 0; isFullscreen = false }) {
                            Text("Свернуть (Back)", color = Color(0xFFBAE6FD))
                        }
                    }
                }
            }
        } else {
            // Стандартный двухпанельный интерфейс ARTUR-TV
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp)) {
                // ВЕРХНЯЯ ПАНЕЛЬ
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Логотип ARTUR-TV
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xCC0C142C))
                                .border(1.5.dp, Color(0xAA7DD3FC), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("▶", color = Color(0xFF7DD3FC), fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("ARTUR-TV", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                            Text("IPTV PLAYER", color = Color(0xFFCBD5E1), fontSize = 11.sp, letterSpacing = 2.8.sp)
                        }
                    }

                    // Индикатор активного плейлиста (например, • only4tv)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { openPlaylistDialog() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF38BDF8)))
                        Spacer(Modifier.width(8.dp))
                        Text(activePlaylist.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }

                    // Правый блок кнопок: число каналов | Архив | Настройки | + Плейлист
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val totalText = activePlaylist.channels.size
                        Text("$totalText каналов", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

                        OutlinedButton(
                            onClick = { showArchiveModal = true },
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFC084FC)),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xAA0F172A))
                        ) {
                            Text("🕒 Архив", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = { showSettingsModal = true },
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8)),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xAA0F172A))
                        ) {
                            Text("⚙ Настройки", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = { openPlaylistDialog() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBAE6FD), contentColor = Color(0xFF0F172A))
                        ) {
                            Text("☰+ Плейлист", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }

                // ОСНОВНАЯ СЕТКА: ЛЕВАЯ БИБЛИОТЕКА КАНАЛОВ + ПРАВЫЙ ЭФИР
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    // ЛЕВАЯ ПАНЕЛЬ КАНАЛОВ
                    Column(
                        modifier = Modifier
                            .width(355.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xB30A1024))
                            .border(1.5.dp, Color(0x887DD3FC), RoundedCornerShape(22.dp))
                            .padding(15.dp)
                    ) {
                        Text("ВАША IPTV-БИБЛИОТЕКА", color = Color(0xFFCBD5E1), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Каналы", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF0F172A))
                                    .border(1.5.dp, Color(0x887DD3FC), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                val badgeNum = filteredChannels.size
                                Text("$badgeNum", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("🔍 Найти канал", fontSize = 14.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .onFocusChanged { searchFocused = it.isFocused }
                        )

                        // Вкладки: Все / Избранное / Недавние
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("Все", "Избранное", "Недавние")) { tab ->
                                FilterChip(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    label = { Text(tab, fontSize = 12.sp) }
                                )
                            }
                        }

                        // Категории над списком каналов. Курсор пульта (focusedCategory) отдельно от выбранной.
                        Text("КАТЕГОРИИ", color = Color(0xFF7DD3FC), fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = 5.dp, bottom = 3.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(groups) { grp ->
                                val active = selectedGroup == grp
                                val focused = focusedCategory == grp
                                FilterChip(
                                    selected = active,
                                    onClick = {
                                        selectedGroup = grp
                                        focusedCategory = grp
                                    },
                                    label = {
                                        Text(
                                            when {
                                                focused -> "▶ $grp"
                                                active -> "✓ $grp"
                                                else -> grp
                                            },
                                            fontSize = 12.sp,
                                            fontWeight = if (active || focused) FontWeight.ExtraBold else FontWeight.Medium
                                        )
                                    },
                                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                        containerColor = Color(0x66202B45),
                                        labelColor = Color(0xFFCBD5E1),
                                        selectedContainerColor = Color(0xFF0EA5E9),
                                        selectedLabelColor = Color.White
                                    ),
                                    border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = active,
                                        borderColor = if (focused) Color.White else Color(0x447DD3FC),
                                        selectedBorderColor = if (focused) Color.White else Color(0xFF38BDF8),
                                        borderWidth = if (focused) 2.5.dp else 1.dp,
                                        selectedBorderWidth = if (focused) 2.5.dp else 1.5.dp
                                    ),
                                    modifier = Modifier
                                        .onFocusChanged { st ->
                                            if (st.isFocused) focusedCategory = grp
                                        }
                                        .border(
                                            width = if (focused) 2.5.dp else 0.dp,
                                            color = if (focused) Color.White else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "КАНАЛЫ · " + if (selectedGroup == "Все") "все" else selectedGroup,
                                color = Color(0xFF7DD3FC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("OK — смотреть", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            items(filteredChannels) { ch ->
                                val idx = filteredChannels.indexOf(ch) + 1
                                val isSelected = ch.key == currentChannel?.key
                                val isFocused = ch.key == focusedChannelKey
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                isSelected -> Color(0x4438BDF8)
                                                isFocused -> Color(0x2838BDF8)
                                                else -> Color.Transparent
                                            }
                                        )
                                        .border(
                                            width = if (isSelected || isFocused) 1.5.dp else 0.dp,
                                            color = when {
                                                isSelected -> Color(0xFF7DD3FC)
                                                isFocused -> Color.White
                                                else -> Color.Transparent
                                            },
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .onFocusChanged { state ->
                                            if (state.isFocused) focusedChannelKey = ch.key
                                        }
                                        .clickable {
                                            val now = System.currentTimeMillis()
                                            if (ch.key == lastClickKey && now - lastClickTime < 450L) {
                                                // Двойной клик → сразу на весь экран
                                                selectChannel(ch)
                                                fsPanel = 0
                                                isFullscreen = true
                                            } else {
                                                // Одиночный клик → смотреть в маленьком окне
                                                selectChannel(ch)
                                            }
                                            lastClickKey = ch.key
                                            lastClickTime = now
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "$idx. ${ch.name}",
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        epgNowTitle(ch)?.let {
                                            Text(
                                                "▸ $it",
                                                color = Color(0xFF94A3B8),
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (favorites.contains(ch.key)) {
                                        Text("★", color = Color(0xFF38BDF8), fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }

                    // ПРАВАЯ КОЛОНКА (ОКНО ПЛЕЕРА + СЕЙЧАС В ЭФИРЕ)
                    Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(0x66070B19))
                                .border(1.5.dp, Color(0x887DD3FC), RoundedCornerShape(22.dp))
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        useController = false
                                        this.resizeMode = resizeMode
                                        runCatching {
                                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                                            setKeepContentOnPlayerReset(true)
                                        }
                                        player = exoPlayer
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                },
                                update = {
                                    it.player = exoPlayer
                                    it.resizeMode = resizeMode
                                },
                                onRelease = { it.player = null },
                                modifier = Modifier.fillMaxSize()
                            )

                            if (isBuffering && playerError == null) {
                                Text(
                                    "Подключаем эфир...",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                            playerError?.let { msg ->
                                Text(
                                    msg,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xCC000000))
                                        .padding(12.dp)
                                )
                            }
                        }

                        // Панель управления ПОД окном плеера — видео не перекрывается
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xCC0A1126))
                                .border(1.dp, Color(0x887DD3FC), RoundedCornerShape(14.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFE0F2FE))
                                        .padding(horizontal = 9.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        "${currentChannel?.number ?: "—"}",
                                        color = Color(0xFF0F172A),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.widthIn(max = 120.dp)) {
                                    Text(
                                        currentChannel?.name ?: "Нет канала",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        currentChannel?.group ?: "",
                                        color = Color(0xFFCBD5E1),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = { stepChannel(-1) },
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7DD3FC)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("‹ Назад", color = Color.White, fontSize = 12.sp) }

                                Button(
                                    onClick = {
                                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0F2FE), contentColor = Color(0xFF0F172A)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) { Text(if (isPlaying) "⏸ Пауза" else "▶ Пуск", fontWeight = FontWeight.Bold, fontSize = 12.sp) }

                                OutlinedButton(
                                    onClick = { stepChannel(1) },
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7DD3FC)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("Вперёд ›", color = Color.White, fontSize = 12.sp) }

                                OutlinedButton(
                                    onClick = {
                                        val k = currentChannel?.key ?: return@OutlinedButton
                                        favorites = if (k in favorites) favorites - k else favorites + k
                                        store.favorites = favorites
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7DD3FC)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        if (currentChannel?.key in favorites) "★" else "☆",
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                }

                                OutlinedButton(
                                    onClick = { fsPanel = 0; isFullscreen = true },
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7DD3FC)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("⛶", color = Color.White, fontSize = 12.sp) }
                            }
                        }

                        // НИЖНИЙ ИНФО-БЛОК "СЕЙЧАС В ЭФИРЕ"
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (shiftSec > 0L) "АРХИВ ЭФИРА" else "СЕЙЧАС В ЭФИРЕ",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xCC0C142C))
                                            .border(1.5.dp, Color(0x887DD3FC), RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("▶", color = Color(0xFF7DD3FC), fontSize = 16.sp)
                                    }
                                    Column {
                                        Text(
                                            currentChannel?.name ?: "Нет канала",
                                            color = Color.White,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        epgNowTitle(currentChannel)?.let {
                                            Text("Сейчас: $it", color = Color(0xFF94A3B8), fontSize = 14.sp)
                                        }
                                        Text(
                                            currentChannel?.group ?: "",
                                            color = Color(0xFFCBD5E1),
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                                Text(
                                    "Пульт: 2×клик = полный экран • ← список • OK смотреть",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp
                                )
                            }

                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(Color(0xCC0C142C))
                                            .border(1.dp, Color(0x887DD3FC), RoundedCornerShape(18.dp))
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF38BDF8)))
                                        Spacer(Modifier.width(6.dp))
                                        Text("IPTV", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        "${currentChannel?.number ?: "—"}",
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }

                                Text(
                                    "⚙ ПЛЕЙЛИСТЫ И НАСТРОЙКИ",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { openPlaylistDialog() }
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Индикатор набора номера канала с пульта (0-9)
        if (numpadDigits.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xEE0F172A))
                    .border(2.dp, Color(0xFF38BDF8), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text("Канал: $numpadDigits", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        // 1. МОДАЛЬНОЕ ОКНО: ДОБАВЛЕНИЕ И ВЫБОР ПЛЕЙЛИСТОВ (ССЫЛКАМИ, ФАЙЛАМИ И ТЕКСТОМ)
        if (showPlaylistModal) {
            var urlName by remember { mutableStateOf("") }
            var urlLink by remember { mutableStateOf("") }
            var rawM3uText by remember { mutableStateOf("") }

            Dialog(onDismissRequest = { showPlaylistModal = false }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF0F172A),
                    modifier = Modifier.fillMaxWidth().border(1.5.dp, Color(0xFF38BDF8), RoundedCornerShape(20.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Плейлисты (Ссылки URL и Файлы)", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { showPlaylistModal = false }) {
                                Text("✕ Закрыть", color = Color(0xFFCBD5E1))
                            }
                        }

                        OutlinedTextField(
                            value = urlName,
                            onValueChange = { urlName = it },
                            label = { Text("Название плейлиста (например, Мой ТВ)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = urlLink,
                            onValueChange = { urlLink = it },
                            label = { Text("Прямая ссылка на .m3u / .m3u8") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = !dialogLoading,
                                onClick = { loadFromUrl(urlName, urlLink) }
                            ) {
                                Text("🔗 Загрузить по ссылке")
                            }

                            OutlinedButton(
                                enabled = !dialogLoading,
                                onClick = {
                                    try {
                                        filePicker.launch(arrayOf("audio/*", "application/*", "text/*", "*/*"))
                                    } catch (_: ActivityNotFoundException) {
                                        dialogStatus = "Файловый менеджер не найден — используйте ссылку или вставку текста ниже"
                                    }
                                }
                            ) {
                                Text("📂 Выбрать файлы .m3u")
                            }

                            if (dialogLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color(0xFF38BDF8))
                            }
                        }

                        // Вставка текста M3U напрямую
                        OutlinedTextField(
                            value = rawM3uText,
                            onValueChange = { rawM3uText = it },
                            label = { Text("Или вставьте текст #EXTM3U / ссылки напрямую") },
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (rawM3uText.isNotBlank()) {
                            OutlinedButton(
                                enabled = !dialogLoading,
                                onClick = {
                                    val text = rawM3uText
                                    val name = urlName
                                    dialogLoading = true
                                    dialogStatus = null
                                    scope.launch {
                                        try {
                                            val parsed = withContext(Dispatchers.Default) { parseM3uPlaylist(text) }
                                            if (parsed.isEmpty()) {
                                                dialogStatus = "Не удалось распознать каналы в вставленном тексте"
                                            } else {
                                                addPlaylist(
                                                    TvPlaylist(
                                                        "raw_${System.currentTimeMillis()}",
                                                        name.ifBlank { "Текстовый плейлист" },
                                                        "Текст",
                                                        parsed,
                                                        parseM3uEpgUrl(text)
                                                    )
                                                )
                                                rawM3uText = ""
                                            }
                                        } catch (e: Exception) {
                                            dialogStatus = "Ошибка разбора: ${e.message ?: e.javaClass.simpleName}"
                                        } finally {
                                            dialogLoading = false
                                        }
                                    }
                                }
                            ) {
                                Text("Сохранить из текста")
                            }
                        }

                        dialogStatus?.let { Text(it, color = Color(0xFFF87171), fontSize = 13.sp) }

                        HorizontalDivider(color = Color(0x3394A3B8))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Подключенные плейлисты:", color = Color(0xFF94A3B8), fontSize = 13.sp)
                            if (playlists.count { it.id != DEMO_ID && !it.id.startsWith("merged_") } > 1) {
                                TextButton(onClick = {
                                    val allCh = playlists
                                        .filter { it.id != DEMO_ID && !it.id.startsWith("merged_") }
                                        .flatMap { it.channels }
                                        .distinctBy { it.key }
                                        .mapIndexed { idx, c -> c.copy(number = idx + 1) }
                                    if (allCh.isNotEmpty()) {
                                        addPlaylist(TvPlaylist("merged_${System.currentTimeMillis()}", "Все плейлисты (${allCh.size})", "Объединенный", allCh,
                                            playlists.firstOrNull { it.epgUrl.isNotBlank() }?.epgUrl ?: ""))
                                    }
                                }) {
                                    Text("Объединить все", color = Color(0xFF38BDF8), fontSize = 12.sp)
                                }
                            }
                        }

                        playlists.forEach { pl ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${pl.name} (${pl.channels.size} кан.)",
                                    color = Color.White,
                                    fontWeight = if (pl.id == activePlaylistId) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { openPlaylist(pl) }
                                        .padding(vertical = 8.dp)
                                )
                                if (pl.id == activePlaylistId) {
                                    Text("Активен", color = Color(0xFF38BDF8), modifier = Modifier.padding(horizontal = 8.dp))
                                }
                                if (pl.id != DEMO_ID) {
                                    TextButton(onClick = { deletePlaylist(pl) }) {
                                        Text("✕", color = Color(0xFFF87171))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. МОДАЛЬНОЕ ОКНО: АРХИВ ЭФИРА (CATCH-UP / TIMESHIFT)
        if (showArchiveModal) {
            val archCh = currentChannel
            Dialog(onDismissRequest = { showArchiveModal = false }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF0F172A),
                    modifier = Modifier.fillMaxWidth().border(1.5.dp, Color(0xFFC084FC), RoundedCornerShape(20.dp))
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("🕒 Архив эфира: ${archCh?.name ?: ""}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        if (archCh == null || !archCh.hasCatchup) {
                            Text(
                                "У этого канала в плейлисте нет архива (нет параметров catchup). " +
                                    "Архив работает только если провайдер указал его в M3U.",
                                color = Color(0xFFCBD5E1),
                                fontSize = 13.sp
                            )
                        } else {
                            val maxSec = if (archCh.catchupDays > 0) archCh.catchupDays * 86400L else Long.MAX_VALUE
                            Text(
                                "Выберите сдвиг назад от текущего момента. Форматы архива у провайдеров разные — " +
                                    "если не играет, значит сервер не поддерживает этот тип.",
                                color = Color(0xFFCBD5E1),
                                fontSize = 13.sp
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    FilterChip(
                                        selected = shiftSec == 0L,
                                        onClick = {
                                            shiftSec = 0L
                                            showArchiveModal = false
                                        },
                                        label = { Text("🔴 Прямой эфир") }
                                    )
                                }
                                items(SHIFT_OPTIONS.filter { it.second <= maxSec }) { opt ->
                                    FilterChip(
                                        selected = shiftSec == opt.second,
                                        onClick = {
                                            shiftSec = opt.second
                                            showArchiveModal = false
                                        },
                                        label = { Text(opt.first) }
                                    )
                                }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showArchiveModal = false }) {
                                Text("Закрыть", color = Color(0xFFCBD5E1))
                            }
                        }
                    }
                }
            }
        }

        // 3. МОДАЛЬНОЕ ОКНО: НАСТРОЙКИ ЭКРАНА И ЗВУКА
        if (showSettingsModal) {
            Dialog(onDismissRequest = { showSettingsModal = false }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF0F172A),
                    modifier = Modifier.fillMaxWidth().border(1.5.dp, Color(0xFF38BDF8), RoundedCornerShape(20.dp))
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("⚙ Настройки плеера", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Масштаб изображения:", color = Color(0xFFCBD5E1), fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT,
                                onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT },
                                label = { Text("16:9 По размеру") }
                            )
                            FilterChip(
                                selected = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL,
                                onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL },
                                label = { Text("Растянуть") }
                            )
                            FilterChip(
                                selected = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                                onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM },
                                label = { Text("Заполнение (Зум)") }
                            )
                        }
                        Text(
                            if (ffmpegAvailable()) "Аудиодекодер: FFmpeg подключён (AC3 / EAC3 / DTS / MP2) + аппаратный MediaCodec."
                            else "Аудиодекодер: FFmpeg не загрузился, работает только аппаратный MediaCodec (без AC3/EAC3/DTS на части устройств).",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showSettingsModal = false }) {
                                Text("Готово", color = Color(0xFF38BDF8))
                            }
                        }
                    }
                }
            }
        }
    }
}
