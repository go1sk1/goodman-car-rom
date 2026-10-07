package kr.goodman.carbridge.transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/** One legacy PCM or H.264 output with explicit open/setup and HU acknowledgment flow control. */
public final class AvOutput implements AutoCloseable {
    public enum State { NEW, OPENING, SETUP, ACTIVE, CLOSED }
    public interface Listener { void active(); void unavailable(String reason); }
    private final AaSession session;
    private final int channel, codec, config, streamId;
    private final Listener listener;
    private State state = State.NEW;
    private int window, outstanding;
    private boolean focused;
    private long lastTimestamp = -1;
    public AvOutput(AaSession session, ServiceCatalog.Channel channel, int codec,
            int configIndex, int streamId, Listener listener) {
        if (channel.kind != ServiceCatalog.Kind.AV_OUTPUT || (codec != 1 && codec != 3)
                || configIndex < 0 || configIndex > 63 || streamId < 1)
            throw new IllegalArgumentException("Invalid output selection");
        this.session = java.util.Objects.requireNonNull(session); this.channel = channel.id;
        this.codec = codec; config = configIndex; this.streamId = streamId;
        this.listener = java.util.Objects.requireNonNull(listener);
    }
    public synchronized State state() { return state; }
    /** The coordinator calls this only after the corresponding vehicle focus grant/loss. */
    public synchronized void setFocused(boolean focused) { this.focused = focused; }
    public synchronized void open() throws IOException {
        if (state != State.NEW) throw new IOException("AV channel already opened");
        send(true, 7, new ProtoFields.Writer().integer(1, 0).integer(2, channel).build());
        state = State.OPENING;
    }
    public synchronized void receive(AaWire.Message message) throws IOException {
        if (message.channel != channel || !message.encrypted || state == State.CLOSED) return;
        List<ProtoFields.Field> fields = ProtoFields.parse(message.body());
        if (message.type() == 8 && message.control && state == State.OPENING) {
            if (ProtoFields.single(fields, 1, true).integer() != 0) { unavailable("Vehicle rejected channel open"); return; }
            send(false, 0x8000, new ProtoFields.Writer().integer(1, codec).build()); state = State.SETUP;
        } else if (message.type() == 0x8003 && !message.control && state == State.SETUP) {
            long status = ProtoFields.single(fields, 1, true).integer();
            long advertisedWindow = ProtoFields.integer(fields, 2, 0);
            List<Long> configurations = ProtoFields.integers(fields, 3);
            if (status != 2 || advertisedWindow < 1 || advertisedWindow > 256
                    || (!configurations.isEmpty() && !configurations.contains((long) config))) {
                unavailable("Vehicle rejected AV format or flow control window"); return;
            }
            window = (int) advertisedWindow;
            send(false, 0x8001, new ProtoFields.Writer().integer(1, streamId).integer(2, config).build());
            state = State.ACTIVE; listener.active();
        } else if (message.type() == 0x8004 && !message.control && state == State.ACTIVE) {
            long id = ProtoFields.single(fields, 1, true).integer();
            if ((id & 255) != (streamId & 255)) return; // Late acknowledgment from an older stream.
            List<Long> stamps = ProtoFields.integers(fields, 3);
            long count = stamps.isEmpty() ? ProtoFields.integer(fields, 2, 0) : stamps.size();
            if (count <= 0 || count > outstanding) throw new IOException("Invalid AV acknowledgment count");
            outstanding -= (int) count;
        }
    }
    /** Nonblocking; caller pauses/drops according to its media policy when the HU window is full. */
    public synchronized boolean offer(byte[] data, long presentationTimeUs) {
        if (state != State.ACTIVE || !focused || outstanding >= window || data == null || data.length == 0
                || data.length > AaWire.MAX_MESSAGE - 10 || presentationTimeUs < 0
                || presentationTimeUs < lastTimestamp) return false;
        byte[] body = ByteBuffer.allocate(data.length + 8).putLong(presentationTimeUs).put(data).array();
        if (!session.offer(channel, false, 0, body)) return false;
        outstanding++; lastTimestamp = presentationTimeUs; return true;
    }
    /** H.264 codec configuration precedes video frames; it is not a timestamped PCM packet. */
    public synchronized boolean configuration(byte[] data) {
        if (codec != 3 || state != State.ACTIVE || !focused || outstanding >= window || data == null
                || data.length == 0 || data.length > 65536) return false;
        if (!session.offer(channel, false, 1, data)) return false;
        outstanding++; return true;
    }
    private void send(boolean control, int type, byte[] data) throws IOException {
        if (!session.offer(channel, control, type, data)) throw new IOException("AV control queue unavailable");
    }
    private void unavailable(String reason) { state = State.CLOSED; outstanding = 0; listener.unavailable(reason); }
    @Override public synchronized void close() {
        if (state == State.ACTIVE) session.offer(channel, false, 0x8002, new byte[0]);
        state = State.CLOSED; outstanding = 0; focused = false;
    }
}
