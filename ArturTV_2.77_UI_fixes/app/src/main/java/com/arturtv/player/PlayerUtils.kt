package com.arturtv.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

private const val TAG = "ArturTV"

/**
 * Быстрый старт канала (параметры перенесены из TapTV 2.35):
 * старт после 0,8 с буфера, повторный старт после ребуфера — 1,5 с,
 * минимальный буфер 1,8 с, максимальный 30 с; время важнее размера.
 */
@OptIn(UnstableApi::class)
private fun fastLiveLoadControl(): DefaultLoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(1_800, 30_000, 800, 1_500)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

/**
 * Разбор MPEG-TS без ожидания IDR-кадра (в TapTV флаги = 73):
 * ALLOW_NON_IDR_KEYFRAMES | DETECT_ACCESS_UNITS | OVERRIDE_CAPTION_DESCRIPTORS.
 * Ускоряет первый кадр на IPTV-потоках с редкими IDR.
 */
@OptIn(UnstableApi::class)
private fun fastTsExtractors(): DefaultExtractorsFactory =
    DefaultExtractorsFactory().setTsExtractorFlags(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS
    )

/**
 * Создаёт ExoPlayer максимально безопасно.
 * FFmpeg (AC3/E-AC3/DTS/MP2/TrueHD) подключён как запасной аудиодекодер: включается только
 * там, где штатный декодер устройства формат не поддерживает. Ошибка загрузки native-библиотеки
 * не приводит к падению — просто остаётся стандартный звук.
 */
@OptIn(UnstableApi::class)
fun buildPlayer(context: Context): ExoPlayer {
    val appCtx = context.applicationContext

    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)

    val audioAttrs = try {
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
    } catch (_: Throwable) {
        null
    }

    // 1) Улучшенный плеер без native-расширений
    try {
        val renderersFactory = DefaultRenderersFactory(appCtx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val trackSelector = DefaultTrackSelector(appCtx).apply {
            try {
                parameters = buildUponParameters()
                    .setTunnelingEnabled(false)
                    .build()
            } catch (t: Throwable) {
                Log.w(TAG, "TrackSelector params failed: ${t.message}")
            }
        }

        val builder = ExoPlayer.Builder(appCtx, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(fastLiveLoadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, fastTsExtractors()))

        if (audioAttrs != null) {
            builder.setAudioAttributes(audioAttrs, true)
        }

        return builder.build().apply {
            playWhenReady = true
            try {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            } catch (_: Throwable) {
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Enhanced player failed, using plain ExoPlayer", t)
    }

    // 2) Минимальный fallback
    return try {
        val builder = ExoPlayer.Builder(appCtx)
            .setLoadControl(fastLiveLoadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, fastTsExtractors()))
        if (audioAttrs != null) {
            builder.setAudioAttributes(audioAttrs, true)
        }
        builder.build().apply { playWhenReady = true }
    } catch (t: Throwable) {
        Log.e(TAG, "Plain player also failed, last resort", t)
        ExoPlayer.Builder(appCtx).build().apply { playWhenReady = true }
    }
}

/**
 * MediaItem для канала: явный MIME для HLS/DASH/TS (быстрее определяется формат),
 * скорость воспроизведения live фиксирована 1.0 — без «догонялок» и искажений звука.
 */
@OptIn(UnstableApi::class)
fun buildMediaItem(url: String): MediaItem {
    val lower = url.lowercase()
    val path = lower.substringBefore('?')
    val b = MediaItem.Builder().setUri(url)
    when {
        lower.contains("m3u8") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
        path.endsWith(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
        path.endsWith(".ts") -> b.setMimeType(MimeTypes.VIDEO_MP2T)
    }
    b.setLiveConfiguration(
        MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1.0f).setMaxPlaybackSpeed(1.0f).build()
    )
    return b.build()
}

val SHIFT_OPTIONS: List<Pair<String, Long>> = listOf(
    "−30 мин" to 1800L,
    "−1 ч" to 3600L,
    "−3 ч" to 10800L,
    "−6 ч" to 21600L,
    "−24 ч" to 86400L
)

private val FLUSSONIC_RE = Regex("^(.*/)([^/?]+)\\.m3u8(\\?.*)?$", RegexOption.IGNORE_CASE)

/**
 * Builds a catch-up URL from common IPTV M3U conventions.
 * Provider-supplied catchup-source always has priority over guessed URL formats.
 */
fun buildPlayUrl(
    ch: TvChannel,
    shiftSec: Long,
    nowSec: Long = System.currentTimeMillis() / 1000
): String {
    if (shiftSec <= 0L || !ch.hasCatchup) return ch.url
    if (ch.catchup.equals("none", ignoreCase = true) ||
        ch.catchup.equals("off", ignoreCase = true) ||
        ch.catchup.equals("false", ignoreCase = true) ||
        ch.catchup == "0") return ch.url

    val start = nowSec - shiftSec
    val vars = mapOf(
        "start" to start,
        "utc" to start,
        "now" to nowSec,
        "timestamp" to nowSec,
        "lutc" to nowSec,
        "end" to nowSec,
        "utcend" to nowSec,
        "offset" to shiftSec,
        "duration" to shiftSec
    )

    fun fill(template: String): String {
        var result = template
        for ((key, value) in vars) {
            result = result.replace("\${$key}", value.toString(), ignoreCase = true)
                .replace("{$key}", value.toString(), ignoreCase = true)
        }
        return result
    }

    fun appendToChannel(template: String): String {
        val value = fill(template.trim())
        if (value.isBlank()) return ch.url
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return when {
            value.startsWith("?") -> ch.url + (if ('?' in ch.url) "&" else "?") + value.drop(1)
            value.startsWith("&") -> ch.url + (if ('?' in ch.url) value else "?" + value.drop(1))
            else -> value
        }
    }

    val source = ch.catchupSource.orEmpty()
    val type = ch.catchup?.lowercase().orEmpty()

    // A provider template is the most reliable source for every catch-up type.
    if (source.isNotBlank()) {
        return when (type) {
            "append", "shift", "timeshift" -> appendToChannel(source)
            else -> {
                val filled = fill(source.trim())
                if (filled.startsWith("?") || filled.startsWith("&")) appendToChannel(source) else filled
            }
        }
    }

    return when (type) {
        "append" -> ch.url
        "shift", "timeshift" -> appendToChannel("?utc={utc}&lutc={lutc}")
        "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> {
            val match = FLUSSONIC_RE.matchEntire(ch.url)
            if (match != null) {
                val streamSegment = match.groupValues[2]
                val streamBase = if (streamSegment.equals("index", true) || streamSegment.equals("playlist", true)) {
                    match.groupValues[1]
                } else {
                    match.groupValues[1] + streamSegment + "/"
                }
                streamBase + "timeshift_abs-$start-$shiftSec.m3u8" + match.groupValues[3]
            } else ch.url
        }
        else -> ch.url
    }
}

/** true, если libffmpegJNI.so загрузилась на этом устройстве. */
fun ffmpegAvailable(): Boolean = try { FfmpegLibrary.isAvailable() } catch (_: Throwable) { false }
