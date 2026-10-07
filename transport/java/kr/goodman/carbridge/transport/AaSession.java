package kr.goodman.carbridge.transport;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;

/** Phone-side legacy v1.1 bootstrap. Channel open/AV negotiation is owned by its listener. */
public final class AaSession implements AutoCloseable {
    public enum State { NEW, VERSION, TLS, AUTH, DISCOVERY, READY, CLOSED }
    public interface Listener {
        /** Runs on the reader thread; application callbacks must be dispatched to their owning thread. */
        void services(ServiceCatalog catalog) throws IOException;
        void message(AaWire.Message message) throws IOException;
        /** Called once, possibly by the write deadline thread. Never contains raw credentials/media. */
        void closed(IOException reason);
    }
    private final ProjectionLink link;
    private final AaTls tls;
    private final ProjectionSender sender;
    private final Listener listener;
    private final Thread reader;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "car-session-deadline"); thread.setDaemon(true); return thread;
    });
    private volatile State state = State.NEW;
    private volatile long receivedAt, stageAt;
    private volatile ServiceCatalog catalog;

    /** Takes ownership of link. A provisioned key and a vehicle trust policy are mandatory. */
    public AaSession(ProjectionLink link, SSLContext context, Listener listener) throws IOException {
        this.link = java.util.Objects.requireNonNull(link);
        this.listener = java.util.Objects.requireNonNull(listener);
        try { tls = new AaTls(context); }
        catch (IOException | RuntimeException failure) { link.close(); throw failure; }
        sender = new ProjectionSender(link, this::stop);
        reader = new Thread(this::read, "car-projection-read");
    }
    public State state() { return state; }
    public synchronized void start() {
        if (state != State.NEW) throw new IllegalStateException("Session already started/closed");
        transition(State.VERSION); receivedAt = System.nanoTime(); sender.start();
        watchdog.scheduleWithFixedDelay(() -> {
            State current = state;
            long now = System.nanoTime();
            if (current != State.CLOSED && (now - receivedAt > TimeUnit.SECONDS.toNanos(20)
                    || (current != State.READY && now - stageAt > TimeUnit.SECONDS.toNanos(30))))
                stop(new IOException("Vehicle session deadline exceeded"));
        }, 1, 1, TimeUnit.SECONDS);
        reader.start();
    }
    /** Caller opens and configures an advertised channel before offering its media. */
    public boolean offer(int channel, boolean control, int type, byte[] body) {
        ServiceCatalog active = catalog;
        if (state != State.READY || active == null || (channel != 0 && !active.channels().containsKey(channel)))
            return false;
        return sender.offer(new AaWire.Message(channel, control, true, type, body));
    }
    private void read() {
        try {
            AaWire.Reader input = new AaWire.Reader(link.input());
            while (state != State.CLOSED) {
                AaWire.Message message = input.read(tls.ready() ? tls : null);
                if (message == null) { stop(null); return; }
                receivedAt = System.nanoTime();
                if (message.channel == 0) control(message);
                else {
                    ServiceCatalog active = catalog;
                    if (state != State.READY || !message.encrypted || active == null
                            || !active.channels().containsKey(message.channel))
                        throw new IOException("Data on an unauthenticated or unadvertised channel");
                    listener.message(message);
                }
            }
        } catch (IOException | RuntimeException failure) {
            stop(failure instanceof IOException ? (IOException) failure : new IOException("Vehicle session failed", failure));
        }
    }
    private void control(AaWire.Message message) throws IOException {
        int type = message.type(); byte[] body = message.body();
        State current = state;
        if (current == State.CLOSED) return;
        if (current == State.VERSION) {
            if (type != 1 || message.encrypted || body.length != 4) throw new IOException("Expected version request");
            int major = ((body[0] & 255) << 8) | (body[1] & 255);
            int minor = ((body[2] & 255) << 8) | (body[3] & 255);
            // Do not advertise modern protocol features that this initial implementation cannot provide.
            if (major != 1 || minor > 1) throw new IOException("Vehicle requires a newer protocol version");
            send(2, new byte[]{0, 1, 0, 1, 0, 0}, false);
            transition(State.TLS); return;
        }
        if (current == State.TLS) {
            if (type != 3 || message.encrypted) throw new IOException("Expected TLS handshake");
            for (byte[] reply : tls.handshake(body)) send(3, reply, false);
            if (tls.ready()) { sender.setCipher(tls); transition(State.AUTH); }
            return;
        }
        if (type == 11 && tls.ready()) {
            List<ProtoFields.Field> request = ProtoFields.parse(body);
            long timestamp = ProtoFields.single(request, 1, true).integer();
            // Reply to liveness only; a remote bugreport flag never starts diagnostics/recording.
            send(12, new ProtoFields.Writer().integer(1, timestamp).build(), message.encrypted); return;
        }
        if (current == State.AUTH) {
            if (type != 4 || ProtoFields.single(ProtoFields.parse(body), 1, true).integer() != 0)
                throw new IOException("Vehicle authentication failed");
            byte[] session = new ProtoFields.Writer().text(1, UUID.randomUUID().toString()).build();
            send(5, new ProtoFields.Writer().text(4, "Goodman Car").text(5, "Goodman")
                    .bytes(6, session).build(), true);
            transition(State.DISCOVERY); return;
        }
        if (!message.encrypted) throw new IOException("Plaintext application message rejected");
        if (current == State.DISCOVERY) {
            if (type != 6) throw new IOException("Expected vehicle service discovery");
            catalog = ServiceCatalog.decode(body); transition(State.READY); listener.services(catalog); return;
        }
        if (current == State.READY) listener.message(message);
    }
    private void send(int type, byte[] body, boolean encrypted) throws IOException {
        // In the legacy protocol, channel 0 messages use SPECIFIC (M=0), not CONTROL (M=1).
        if (!sender.offer(new AaWire.Message(0, false, encrypted, type, body))) throw new IOException("Control queue unavailable");
    }
    private synchronized void transition(State next) {
        if (state != State.CLOSED) { state = next; stageAt = System.nanoTime(); }
    }
    private void stop(IOException reason) {
        synchronized (this) {
            if (state == State.CLOSED) return;
            state = State.CLOSED; catalog = null;
        }
        sender.close(); tls.close(); watchdog.shutdownNow(); listener.closed(reason);
    }
    @Override public void close() { stop(null); }
}
