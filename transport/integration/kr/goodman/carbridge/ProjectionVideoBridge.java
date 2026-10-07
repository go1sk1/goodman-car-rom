package kr.goodman.carbridge;

import android.content.Context;
import android.media.MediaCodec;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;
import kr.goodman.carbridge.transport.AaSession;
import kr.goodman.carbridge.transport.AaWire;
import kr.goodman.carbridge.transport.AvOutput;
import kr.goodman.carbridge.transport.ServiceCatalog;

/** Connects negotiated legacy AVC output to ScreenEncoder; owner supplies vehicle focus grants. */
final class ProjectionVideoBridge implements AvOutput.Listener, AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScreenEncoder encoder;
    private final ServiceCatalog.Video video;
    private final AvOutput output;
    private final Consumer<String> status;
    private final Runnable ready;
    private boolean visible, encoding, closed, failed;
    // Written by main and read by the encoder. A loss/regain invalidates in-flight delta frames.
    private volatile long focusEpoch;
    private volatile boolean captureAllowed;

    ProjectionVideoBridge(Context context, AaSession session, ServiceCatalog.Channel channel,
            ServiceCatalog.Video video, int streamId, Consumer<String> status, Runnable ready) {
        requireMain();
        encoder = new ScreenEncoder(context);
        this.video = java.util.Objects.requireNonNull(video);
        this.status = java.util.Objects.requireNonNull(status);
        this.ready = java.util.Objects.requireNonNull(ready);
        output = new AvOutput(session, channel, 3, video.configIndex, streamId, this);
    }

    void open() throws IOException { requireMain(); output.open(); }
    /** Called on the authenticated session reader, before returning to its read loop. */
    void receive(AaWire.Message message) throws IOException { output.receive(message); }

    void setVisible(boolean granted) {
        requireMain();
        if (closed || failed || visible == granted) return;
        visible = granted; captureAllowed = granted; focusEpoch++;
        output.setFocused(granted);
        if (!granted) encoder.stop();
        else startIfReady();
    }

    @Override public void active() { main.post(() -> {
        if (closed || failed) return;
        ready.run(); startIfReady();
    }); }
    @Override public void unavailable(String reason) { main.post(() -> fail(reason)); }

    private void startIfReady() {
        requireMain();
        if (closed || failed || encoding || !visible || output.state() != AvOutput.State.ACTIVE) return;
        encoding = true;
        try { encoder.start(video.width, video.height, video.densityDpi, new VideoSink()); }
        catch (RuntimeException failure) { encoding = false; fail("화면 캡처 시작 실패"); }
    }

    private final class VideoSink implements ScreenEncoder.Sink {
        private byte[] configuration;
        private boolean configurationSent, needsKeyFrame = true;
        private long observedEpoch = focusEpoch;

        @Override public void format(int width, int height, byte[] csd0, byte[] csd1) throws IOException {
            if (width != video.width || height != video.height || csd0 == null || csd1 == null
                    || csd0.length == 0 || csd0.length + csd1.length > 65536)
                throw new IOException("Unexpected AVC configuration");
            // AVC encoders expose Annex-B parameter sets for this byte-stream sink.
            if (!annexB(csd0) || (csd1.length != 0 && !annexB(csd1)))
                throw new IOException("Encoder did not provide Annex-B AVC parameter sets");
            configuration = Arrays.copyOf(csd0, csd0.length + csd1.length);
            System.arraycopy(csd1, 0, configuration, csd0.length, csd1.length);
            configurationSent = false; needsKeyFrame = true;
        }

        @Override public void frame(byte[] data, long presentationTimeUs, int flags) throws IOException {
            long epoch = focusEpoch;
            if (!captureAllowed) { needsKeyFrame = true; return; }
            if (epoch != observedEpoch) {
                observedEpoch = epoch; configurationSent = false; needsKeyFrame = true;
            }
            if (configuration == null) throw new IOException("AVC frame before codec configuration");
            boolean keyFrame = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            if (needsKeyFrame && !keyFrame) return;
            if (!configurationSent) {
                if (!output.configuration(configuration)) return;
                configurationSent = true;
            }
            if (!annexB(data)) throw new IOException("Encoder did not provide Annex-B AVC frames");
            // If one predicted frame is dropped, skip subsequent predicted frames until a fresh IDR.
            // No extra unbounded video queue: the channel already has a negotiated ACK window.
            if (output.offer(data, presentationTimeUs)) needsKeyFrame = false;
            else needsKeyFrame = true;
        }

        @Override public void stopped(Exception failure) {
            main.post(() -> {
                encoding = false;
                if (closed) return;
                if (failure != null) fail("화면 캡처 또는 전송을 시작하지 못했습니다");
                else startIfReady(); // Handles focus returning while the old encoder was stopping.
            });
        }
    }

    private static boolean annexB(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes[0] == 0 && bytes[1] == 0
                && (bytes[2] == 1 || (bytes[2] == 0 && bytes[3] == 1));
    }

    private void fail(String reason) {
        requireMain();
        if (closed || failed) return;
        failed = true; captureAllowed = false; focusEpoch++;
        encoder.stop(); output.close(); status.accept(reason);
    }

    @Override public void close() {
        requireMain();
        if (closed) return;
        closed = true; captureAllowed = false; focusEpoch++;
        encoder.stop(); output.close();
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Video control requires main thread");
    }
}
