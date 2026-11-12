package androidx.media3.decoder.ffmpeg;

import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;

/**
 * Запасной аудиорендерер: работает только если штатный MediaCodec-декодер
 * устройства не умеет формат (AC3/E-AC3/DTS/MP2/TrueHD и т.п.).
 * Конструктор (Handler, AudioRendererEventListener, AudioSink) нужен
 * DefaultRenderersFactory — он создаёт класс по имени через reflection.
 */
@UnstableApi
public final class FfmpegAudioRenderer extends DecoderAudioRenderer<FfmpegAudioDecoder> {
    private static final int NUM_BUFFERS = 16;
    private static final int DEFAULT_INPUT_BUFFER_SIZE = 960 * 6;

    public FfmpegAudioRenderer(@Nullable Handler handler,
                               @Nullable AudioRendererEventListener listener,
                               AudioSink sink) {
        super(handler, listener, sink);
    }

    @Override public String getName() { return "FfmpegAudioRenderer"; }

    @Override
    protected int supportsFormatInternal(Format format) {
        String mime = format.sampleMimeType;
        if (mime == null || !MimeTypes.isAudio(mime) || !FfmpegLibrary.isAvailable()) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
        }
        if (!FfmpegLibrary.supportsFormat(mime)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
        }
        if (!sinkSupportsFormat(format, C.ENCODING_PCM_16BIT)
                && !sinkSupportsFormat(format, C.ENCODING_PCM_FLOAT)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
        }
        return RendererCapabilities.create(C.FORMAT_HANDLED);
    }

    @Override
    public int supportsMixedMimeTypeAdaptation() { return ADAPTIVE_NOT_SEAMLESS; }

    @Override
    protected FfmpegAudioDecoder createDecoder(Format format, @Nullable CryptoConfig crypto)
            throws FfmpegDecoderException {
        TraceUtil.beginSection("createFfmpegAudioDecoder");
        int inputSize = format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
        FfmpegAudioDecoder d = new FfmpegAudioDecoder(format, NUM_BUFFERS, NUM_BUFFERS, inputSize,
                shouldOutputFloat(format));
        TraceUtil.endSection();
        return d;
    }

    @Override
    protected Format getOutputFormat(FfmpegAudioDecoder decoder) {
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(decoder.getChannelCount())
                .setSampleRate(decoder.getSampleRate())
                .setPcmEncoding(decoder.getEncoding())
                .build();
    }

    private boolean sinkSupportsFormat(Format format, @C.PcmEncoding int pcm) {
        return sinkSupportsFormat(Util.getPcmFormat(pcm, format.channelCount, format.sampleRate));
    }

    private boolean shouldOutputFloat(Format format) {
        if (!sinkSupportsFormat(format, C.ENCODING_PCM_16BIT)) return true;
        int floatSupport = getSinkFormatSupport(
                Util.getPcmFormat(C.ENCODING_PCM_FLOAT, format.channelCount, format.sampleRate));
        if (floatSupport != AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY) return false;
        // AC3 даёт артефакты при float-выводе — оставляем 16 бит.
        return !MimeTypes.AUDIO_AC3.equals(format.sampleMimeType);
    }
}
