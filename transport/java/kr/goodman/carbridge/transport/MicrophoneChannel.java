package kr.goodman.carbridge.transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/** Requested-only vehicle PCM input. The caller stops requests on mute, privacy changes, or recording end. */
public final class MicrophoneChannel implements AutoCloseable {
    public enum ResponseLayout { LEGACY_SESSION_FIRST, STATUS_THEN_SESSION }
    public interface Listener {
        default void ready() {}
        /** Bounded, nonblocking handoff to the Android recording/HFP uplink path. */
        boolean pcm(byte[] pcm, long headUnitTimestampUs);
        void unavailable(String reason);
    }
    private enum State { NEW, OPENING, SETUP, IDLE, STARTING, ACTIVE, CLOSED }
    private final AaSession session;
    private final int channel, frameBytes;
    private final ResponseLayout responseLayout;
    private final Listener listener;
    private State state = State.NEW;
    private boolean wanted, hasStream;
    private int streamId;
    private long lastTimestamp = -1;

    public MicrophoneChannel(AaSession session, ServiceCatalog.Channel channel,
            ResponseLayout responseLayout, Listener listener) throws IOException {
        if (channel.kind != ServiceCatalog.Kind.AV_INPUT) throw new IOException("Not a vehicle microphone service");
        List<ServiceCatalog.Pcm> formats = ServiceCatalog.pcmConfigurations(channel);
        if (formats.size() != 1) throw new IOException("Vehicle microphone must advertise one supported PCM format");
        this.session = java.util.Objects.requireNonNull(session); this.channel = channel.id;
        this.responseLayout = java.util.Objects.requireNonNull(responseLayout);
        this.listener = java.util.Objects.requireNonNull(listener); frameBytes = formats.get(0).channels * 2;
    }
    public synchronized void open() throws IOException {
        if (state != State.NEW) throw new IOException("Microphone channel already opened");
        send(true, 7, new ProtoFields.Writer().integer(1, 0).integer(2, channel).build()); state = State.OPENING;
    }
    public synchronized void request(boolean active) throws IOException {
        if (state == State.CLOSED) return;
        wanted = active;
        if (active && state == State.IDLE) start();
        else if (!active && state == State.ACTIVE) {
            send(false, 0x8005, new ProtoFields.Writer().integer(1, 0).build()); state = State.IDLE;
        }
        // During STARTING, wait for its reply before issuing the matching close. PCM delivery is already disabled.
    }
    private void start() throws IOException {
        send(false, 0x8005, new ProtoFields.Writer().integer(1, 1).integer(2, 0).integer(3, 0).integer(4, 8).build());
        state = State.STARTING;
    }
    public synchronized void receive(AaWire.Message message) throws IOException {
        if (message.channel != channel || !message.encrypted || state == State.CLOSED) return;
        int type = message.type(); byte[] body = message.body();
        if (type == 0 && !message.control) {
            if (!hasStream) throw new IOException("Microphone PCM before an accepted session");
            if (body.length <= 8 || body.length > 19208 || (body.length - 8) % frameBytes != 0)
                throw new IOException("Invalid microphone PCM frame");
            long timestamp = ByteBuffer.wrap(body, 0, 8).getLong();
            if (timestamp < 0 || timestamp < lastTimestamp) throw new IOException("Out-of-order microphone frame");
            lastTimestamp = timestamp;
            if (wanted && state == State.ACTIVE && !listener.pcm(Arrays.copyOfRange(body, 8, body.length), timestamp)) {
                request(false); listener.unavailable("Android microphone queue is full");
            }
            // Acknowledge dropped late frames too; no unwanted microphone data reaches Android.
            send(false, 0x8004, new ProtoFields.Writer().integer(1, streamId).integer(2, 1).build());
            return;
        }
        List<ProtoFields.Field> fields = ProtoFields.parse(body);
        if (type == 8 && message.control && state == State.OPENING) {
            if (ProtoFields.single(fields, 1, true).integer() != 0) { rejected("Vehicle rejected microphone channel"); return; }
            send(false, 0x8000, new ProtoFields.Writer().integer(1, 1).build()); state = State.SETUP;
        } else if (type == 0x8003 && !message.control && state == State.SETUP) {
            if (ProtoFields.single(fields, 1, true).integer() != 2) { rejected("Vehicle rejected microphone PCM format"); return; }
            state = State.IDLE; listener.ready(); if (wanted) start();
        } else if (type == 0x8006 && !message.control && state == State.STARTING) {
            long first = ProtoFields.single(fields, 1, true).integer();
            long id;
            if (responseLayout == ResponseLayout.STATUS_THEN_SESSION) {
                if (first != 0) { rejected("Vehicle microphone open failed"); return; }
                id = ProtoFields.single(fields, 2, true).integer();
            } else id = first;
            if (id < 0 || id > Integer.MAX_VALUE) throw new IOException("Invalid microphone session ID");
            streamId = (int) id; hasStream = true; lastTimestamp = -1; state = State.ACTIVE;
            if (!wanted) request(false);
        }
    }
    private void send(boolean control, int type, byte[] body) throws IOException {
        if (!session.offer(channel, control, type, body)) throw new IOException("Microphone control queue unavailable");
    }
    private void rejected(String reason) { state = State.CLOSED; wanted = false; listener.unavailable(reason); }
    public synchronized boolean ready() {
        return state == State.IDLE || state == State.STARTING || state == State.ACTIVE;
    }
    @Override public synchronized void close() {
        wanted = false;
        if (state == State.ACTIVE || state == State.STARTING)
            session.offer(channel, false, 0x8005, new ProtoFields.Writer().integer(1, 0).build());
        state = State.CLOSED; hasStream = false;
    }
}
