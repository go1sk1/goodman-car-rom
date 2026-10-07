package kr.goodman.carbridge;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;
import java.nio.ByteBuffer;

/** System capture stage. A negotiated vehicle transport must supply the sink. */
final class ScreenEncoder {
    /** Sink implementations must use bounded writes/queues so stop can finish. */
    interface Sink {
        void format(int width, int height, byte[] csd0, byte[] csd1) throws Exception;
        void frame(byte[] data, long presentationTimeUs, int flags) throws Exception;
        void stopped(Exception failure);
    }

    private final Context context;
    private volatile boolean running;
    private Thread worker;

    ScreenEncoder(Context context) { this.context = context; }

    synchronized void start(int width, int height, int densityDpi, Sink sink) {
        if (worker != null) throw new IllegalStateException("Capture session already active");
        if (width < 2 || height < 2 || width > 3840 || height > 2160
                || (width & 1) != 0 || (height & 1) != 0 || densityDpi <= 0 || sink == null) {
            throw new IllegalArgumentException("Invalid negotiated video configuration");
        }
        running = true;
        worker = new Thread(() -> encode(width, height, densityDpi, sink), "car-video");
        worker.start();
    }

    void stop() { running = false; }

    private void encode(int width, int height, int density, Sink sink) {
        MediaCodec encoder = null;
        Surface input = null;
        VirtualDisplay display = null;
        Exception failure = null;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = encoder.createInputSurface();
            encoder.start();
            // Requires our platform-signed CAPTURE_VIDEO_OUTPUT permission.
            // Secure/DRM surfaces are deliberately not requested.
            display = context.getSystemService(DisplayManager.class).createVirtualDisplay(
                    "GoodmanCarMirror", width, height, density, input,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
            if (display == null) throw new IllegalStateException("System display capture unavailable");
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                int index = encoder.dequeueOutputBuffer(info, 100_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = encoder.getOutputFormat();
                    sink.format(width, height, bytes(output.getByteBuffer("csd-0")), bytes(output.getByteBuffer("csd-1")));
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            ByteBuffer buffer = encoder.getOutputBuffer(index);
                            if (buffer == null) throw new IllegalStateException("No encoded video buffer");
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            sink.frame(bytes(buffer), info.presentationTimeUs, info.flags);
                        }
                    } finally { encoder.releaseOutputBuffer(index, false); }
                }
            }
        } catch (Exception e) {
            failure = e;
        } finally {
            // The worker owns and releases codec/display resources; stop() never races release.
            if (display != null) display.release();
            if (encoder != null) {
                try { encoder.stop(); } catch (IllegalStateException ignored) { }
                encoder.release();
            }
            if (input != null) input.release();
            synchronized (this) { running = false; worker = null; }
            sink.stopped(failure);
        }
    }

    private static byte[] bytes(ByteBuffer source) {
        if (source == null) return new byte[0];
        ByteBuffer copy = source.duplicate();
        byte[] data = new byte[copy.remaining()];
        copy.get(data);
        return data;
    }
}
