package com.arturtv.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

private const val TAG = "ArturTV"

/**
 * Создаёт ExoPlayer максимально безопасно.
 * Без FFmpeg-native (он ронял приложение на старте на части устройств).
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
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

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
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))

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
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        if (audioAttrs != null) {
            builder.setAudioAttributes(audioAttrs, true)
        }
        builder.build().apply { playWhenReady = true }
    } catch (t: Throwable) {
        Log.e(TAG, "Plain player also failed, last resort", t)
        ExoPlayer.Builder(appCtx).build().apply { playWhenReady = true }
    }
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
    // hasCatchup already treats none/off/false/0 as unavailable.
    if (shiftSec <= 0L || !ch.hasCatchup) return ch.url

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

/** FFmpeg не подключён в зависимостях — без native-краша на старте. */
fun ffmpegAvailable(): Boolean = false
