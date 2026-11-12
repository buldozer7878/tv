package androidx.media3.decoder.ffmpeg;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.Mp4Box;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.List;

/** Аудиодекодер на FFmpeg (JNI из libffmpegJNI.so). */
@UnstableApi
final class FfmpegAudioDecoder
        extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException> {

    private static final int ERR_INVALID_DATA = -1;
    private static final int ERR_OTHER = -2;
    private static final int OUT_16BIT = 65535;
    private static final int OUT_32BIT = 131070;

    private final String codecName;
    @Nullable private final byte[] extraData;
    private final int encoding;
    private int outputBufferSize;
    private long nativeContext;
    private boolean hasOutputFormat;
    private volatile int channelCount;
    private volatile int sampleRate;

    FfmpegAudioDecoder(Format format, int numInput, int numOutput, int initialInputSize,
                       boolean outputFloat) throws FfmpegDecoderException {
        super(new DecoderInputBuffer[numInput], new SimpleDecoderOutputBuffer[numOutput]);
        if (!FfmpegLibrary.isAvailable()) {
            throw new FfmpegDecoderException("Failed to load decoder native libraries.");
        }
        Assertions.checkNotNull(format.sampleMimeType);
        codecName = Assertions.checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType));
        extraData = getExtraData(format.sampleMimeType, format.initializationData);
        encoding = outputFloat ? 4 /* C.ENCODING_PCM_FLOAT */ : 2 /* C.ENCODING_PCM_16BIT */;
        outputBufferSize = outputFloat ? OUT_32BIT : OUT_16BIT;
        nativeContext = ffmpegInitialize(codecName, extraData, outputFloat, format.sampleRate, format.channelCount);
        if (nativeContext == 0) throw new FfmpegDecoderException("Initialization failed.");
        setInitialInputBufferSize(initialInputSize);
    }

    @Override public String getName() { return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName; }

    @Override protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
                FfmpegLibrary.getInputBufferPaddingSize());
    }

    @Override protected SimpleDecoderOutputBuffer createOutputBuffer() {
        return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
    }

    @Override protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
        return new FfmpegDecoderException("Unexpected decode error", error);
    }

    @Override @Nullable
    protected FfmpegDecoderException decode(DecoderInputBuffer in, SimpleDecoderOutputBuffer out, boolean reset) {
        if (reset) {
            nativeContext = ffmpegReset(nativeContext, extraData);
            if (nativeContext == 0) return new FfmpegDecoderException("Error resetting (see logcat).");
        }
        ByteBuffer inputData = Util.castNonNull(in.data);
        int inputSize = inputData.limit();
        ByteBuffer outputData = out.init(in.timeUs, outputBufferSize);
        int result = ffmpegDecode(nativeContext, inputData, inputSize, out, outputData, outputBufferSize);
        if (result == ERR_OTHER) return new FfmpegDecoderException("Error decoding (see logcat).");
        if (result == ERR_INVALID_DATA || result == 0) {
            out.shouldBeSkipped = true;
            return null;
        }
        if (!hasOutputFormat) {
            channelCount = ffmpegGetChannelCount(nativeContext);
            sampleRate = ffmpegGetSampleRate(nativeContext);
            if (sampleRate == 0 && "alac".equals(codecName)) {
                Assertions.checkNotNull(extraData);
                ParsableByteArray p = new ParsableByteArray(extraData);
                p.setPosition(extraData.length - 4);
                sampleRate = p.readUnsignedIntToInt();
            }
            hasOutputFormat = true;
        }
        ByteBuffer o = Assertions.checkNotNull(out.data);
        o.position(0);
        o.limit(result);
        return null;
    }

    @Override public void release() {
        super.release();
        ffmpegRelease(nativeContext);
        nativeContext = 0;
    }

    int getChannelCount() { return channelCount; }
    int getSampleRate() { return sampleRate; }
    int getEncoding() { return encoding; }

    @Nullable
    private static byte[] getExtraData(String mimeType, List<byte[]> init) {
        switch (mimeType) {
            case MimeTypes.AUDIO_AAC:
            case MimeTypes.AUDIO_OPUS:
                return init.isEmpty() ? null : init.get(0);
            case MimeTypes.AUDIO_ALAC: {
                byte[] b = init.get(0);
                int len = b.length + 12;
                ByteBuffer bb = ByteBuffer.allocate(len);
                bb.putInt(len);
                bb.putInt(Mp4Box.TYPE_alac);
                bb.putInt(0);
                bb.put(b, 0, b.length);
                return bb.array();
            }
            case MimeTypes.AUDIO_VORBIS: {
                byte[] a = init.get(0), b = init.get(1);
                byte[] r = new byte[a.length + b.length + 6];
                r[0] = (byte) (a.length >> 8);
                r[1] = (byte) (a.length & 0xFF);
                System.arraycopy(a, 0, r, 2, a.length);
                r[a.length + 2] = 0;
                r[a.length + 3] = 0;
                r[a.length + 4] = (byte) (b.length >> 8);
                r[a.length + 5] = (byte) (b.length & 0xFF);
                System.arraycopy(b, 0, r, a.length + 6, b.length);
                return r;
            }
            default:
                return null;
        }
    }

    private native long ffmpegInitialize(String codecName, @Nullable byte[] extraData,
                                         boolean outputFloat, int rawSampleRate, int rawChannelCount);
    private native int ffmpegDecode(long context, ByteBuffer inputData, int inputSize,
                                    SimpleDecoderOutputBuffer outputBuffer, ByteBuffer outputData, int outputSize);
    private native int ffmpegGetChannelCount(long context);
    private native int ffmpegGetSampleRate(long context);
    private native long ffmpegReset(long context, @Nullable byte[] extraData);
    private native void ffmpegRelease(long context);
}
