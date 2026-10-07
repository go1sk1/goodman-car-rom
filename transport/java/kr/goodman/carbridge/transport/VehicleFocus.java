package kr.goodman.carbridge.transport;

import java.io.IOException;
import java.util.List;

/** Vehicle focus grants are separate from Android app audio focus and AV format setup. */
public final class VehicleFocus {
    public enum AudioRequest { GAIN(1), TRANSIENT(2), MAY_DUCK(3), RELEASE(4);
        final int wire; AudioRequest(int wire) { this.wire = wire; }
    }
    public enum AudioState { GAIN, TRANSIENT, LOSS, MAY_DUCK, LOSS_TRANSIENT, MEDIA_ONLY, GUIDANCE_ONLY }
    public interface Listener {
        /** Coordinator enables only outputs allowed by this grant, and handles attenuation for MAY_DUCK. */
        void audio(AudioState state);
        void video(boolean visible, boolean inputAllowed);
    }
    private final AaSession session;
    private final int videoChannel;
    private final int displayId;
    private final Listener listener;
    private boolean closed;
    public VehicleFocus(AaSession session, int advertisedVideoChannel, Listener listener) {
        this(session, advertisedVideoChannel, 0, listener);
    }
    public VehicleFocus(AaSession session, int advertisedVideoChannel, int displayId, Listener listener) {
        if (advertisedVideoChannel < 1 || advertisedVideoChannel > 255) throw new IllegalArgumentException("Invalid video channel");
        if (displayId < 0) throw new IllegalArgumentException("Invalid display ID");
        this.session = java.util.Objects.requireNonNull(session); videoChannel = advertisedVideoChannel;
        this.displayId = displayId;
        this.listener = java.util.Objects.requireNonNull(listener);
    }
    public synchronized void requestAudio(AudioRequest request) throws IOException {
        if (closed) throw new IOException("Focus session closed");
        if (request == AudioRequest.RELEASE) listener.audio(AudioState.LOSS);
        if (!session.offer(0, false, 0x12, new ProtoFields.Writer().integer(1, request.wire).build()))
            throw new IOException("Vehicle audio focus queue unavailable");
    }
    public synchronized void requestVideo(boolean projected) throws IOException {
        if (closed) throw new IOException("Focus session closed");
        // The caller suspends capture locally. Only an HU indication confirms the switch.
        // VideoFocusRequest: 1=legacy display index, 2=focus mode, 3=reason (2=launch native).
        if (!session.offer(videoChannel, false, 0x8007, new ProtoFields.Writer()
                .integer(1, displayId).integer(2, projected ? 1 : 2).integer(3, projected ? 0 : 2).build()))
            throw new IOException("Vehicle video focus queue unavailable");
    }
    public synchronized void receive(AaWire.Message message) throws IOException {
        if (closed || !message.encrypted || message.control) return;
        if (message.channel == 0 && message.type() == 0x13) {
            List<ProtoFields.Field> fields = ProtoFields.parse(message.body());
            long state = ProtoFields.single(fields, 1, true).integer();
            long granted = ProtoFields.integer(fields, 2, 1);
            if (granted != 0 && granted != 1) throw new IOException("Invalid audio focus grant");
            if (granted == 0 || state < 1 || state > 7) { listener.audio(AudioState.LOSS); return; }
            listener.audio(AudioState.values()[(int) state - 1]);
        } else if (message.channel == videoChannel && message.type() == 0x8008) {
            long mode = ProtoFields.single(ProtoFields.parse(message.body()), 1, true).integer();
            listener.video(mode == 1 || mode == 4, mode == 1);
        }
    }
    public synchronized void close() {
        if (closed) return;
        closed = true; listener.audio(AudioState.LOSS); listener.video(false, false);
    }
}
