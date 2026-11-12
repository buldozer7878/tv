package androidx.media3.decoder.ffmpeg;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.LibraryLoader;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;

/**
 * Загрузка libffmpegJNI.so (взята из TapTV 2.35). Любая ошибка загрузки
 * превращается в «библиотека недоступна», приложение не падает.
 */
@UnstableApi
public final class FfmpegLibrary {
    static { MediaLibraryInfo.registerModule("media3.decoder.ffmpeg"); }

    private static final String TAG = "FfmpegLibrary";
    private static final LibraryLoader LOADER = new LibraryLoader("ffmpegJNI");
    private static int inputBufferPaddingSize = -1;
    @Nullable private static String version;

    private FfmpegLibrary() {}

    public static boolean isAvailable() {
        try {
            return LOADER.isAvailable();
        } catch (Throwable t) {
            Log.e(TAG, "ffmpegJNI load failed: " + t);
            return false;
        }
    }

    @Nullable
    public static String getVersion() {
        if (!isAvailable()) return null;
        if (version == null) version = ffmpegGetVersion();
        return version;
    }

    public static int getInputBufferPaddingSize() {
        if (!isAvailable()) return -1;
        if (inputBufferPaddingSize == -1) inputBufferPaddingSize = ffmpegGetInputBufferPaddingSize();
        return inputBufferPaddingSize;
    }

    public static boolean supportsFormat(String mimeType) {
        String codec = getCodecName(mimeType);
        if (codec == null || !isAvailable()) return false;
        try {
            if (!ffmpegHasDecoder(codec)) {
                Log.w(TAG, "No FFmpeg decoder for " + codec);
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    static String getCodecName(String mimeType) {
        switch (mimeType) {
            case MimeTypes.AUDIO_AAC: return "aac";
            case MimeTypes.AUDIO_AC3: return "ac3";
            case MimeTypes.AUDIO_E_AC3:
            case MimeTypes.AUDIO_E_AC3_JOC: return "eac3";
            case MimeTypes.AUDIO_DTS:
            case MimeTypes.AUDIO_DTS_HD:
            case MimeTypes.AUDIO_DTS_EXPRESS: return "dca";
            case MimeTypes.AUDIO_TRUEHD: return "truehd";
            case MimeTypes.AUDIO_MPEG: return "mp3";
            case MimeTypes.AUDIO_MPEG_L2: return has("mp2") ? "mp2" : (has("mp2float") ? "mp2float" : null);
            case MimeTypes.AUDIO_MPEG_L1: return has("mp1") ? "mp1" : null;
            case MimeTypes.AUDIO_VORBIS: return "vorbis";
            case MimeTypes.AUDIO_OPUS: return "opus";
            case MimeTypes.AUDIO_FLAC: return "flac";
            case MimeTypes.AUDIO_ALAC: return "alac";
            case MimeTypes.AUDIO_AMR_NB: return "amrnb";
            case MimeTypes.AUDIO_AMR_WB: return "amrwb";
            case MimeTypes.AUDIO_ALAW: return "pcm_alaw";
            case MimeTypes.AUDIO_MLAW: return "pcm_mulaw";
            default: return null;
        }
    }

    private static boolean has(String codec) {
        if (!isAvailable()) return false;
        try { return ffmpegHasDecoder(codec); } catch (Throwable t) { return false; }
    }

    private static native String ffmpegGetVersion();
    private static native int ffmpegGetInputBufferPaddingSize();
    private static native boolean ffmpegHasDecoder(String codecName);
}
