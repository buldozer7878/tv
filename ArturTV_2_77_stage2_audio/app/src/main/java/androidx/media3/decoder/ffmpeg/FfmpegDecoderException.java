package androidx.media3.decoder.ffmpeg;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;

/** Ошибка FFmpeg-декодера. */
@UnstableApi
public final class FfmpegDecoderException extends DecoderException {
    public FfmpegDecoderException(String message) { super(message); }
    public FfmpegDecoderException(String message, Throwable cause) { super(message, cause); }
}
