package kr.goodman.carbridge.transport;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Bounded nonblocking offers; a blocked USB/network write closes the owned connection. */
public final class ProjectionSender implements AutoCloseable {
    private static final int MAX_BYTES = 8 * 1024 * 1024, MAX_MESSAGES = 128;
    public interface Failure { void stopped(IOException reason); }
    private final ProjectionLink link;
    private final Failure failure;
    private final ArrayDeque<AaWire.Message> controls = new ArrayDeque<>(), media = new ArrayDeque<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "car-send-deadline"); thread.setDaemon(true); return thread;
    });
    private final Thread writer;
    private AaWire.Cipher cipher;
    private int queuedBytes;
    private boolean closed, started;
    public ProjectionSender(ProjectionLink link, Failure failure) {
        this.link = java.util.Objects.requireNonNull(link);
        this.failure = java.util.Objects.requireNonNull(failure);
        writer = new Thread(this::run, "car-projection-send");
    }
    public synchronized void start() {
        if (closed || started) throw new IllegalStateException("Sender already started/closed");
        started = true; writer.start();
    }
    public synchronized void setCipher(AaWire.Cipher cipher) {
        if (closed || this.cipher != null || cipher == null) throw new IllegalStateException("Invalid TLS transition");
        this.cipher = cipher;
    }
    public synchronized boolean offer(AaWire.Message message) {
        if (closed || !started || message == null || (message.encrypted && cipher == null)
                || controls.size() + media.size() >= MAX_MESSAGES
                || message.size() > MAX_BYTES - queuedBytes) return false;
        // Media cannot exhaust the space reserved for ping, flow control, and orderly shutdown.
        boolean priority = message.channel == 0 || message.control;
        if (!priority && (media.size() >= 96 || queuedBytes + message.size() > MAX_BYTES - 65536)) return false;
        (priority ? controls : media).addLast(message); queuedBytes += message.size();
        notifyAll(); return true;
    }
    private void run() {
        try {
            while (true) {
                AaWire.Message message;
                AaWire.Cipher selected;
                synchronized (this) {
                    while (!closed && controls.isEmpty() && media.isEmpty()) wait();
                    if (closed) return;
                    message = controls.isEmpty() ? media.removeFirst() : controls.removeFirst();
                    queuedBytes -= message.size(); selected = cipher;
                }
                ScheduledFuture<?> deadline = deadlines.schedule(
                        () -> stop(new IOException("Vehicle write deadline exceeded")), 5, TimeUnit.SECONDS);
                try { AaWire.write(link.output(), message, selected); }
                finally { deadline.cancel(false); }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); stop(new IOException("Vehicle sender interrupted"));
        } catch (IOException | RuntimeException error) {
            stop(error instanceof IOException ? (IOException) error : new IOException("Vehicle sender failed", error));
        }
    }
    private void stop(IOException reason) {
        synchronized (this) {
            if (closed) return;
            closed = true; controls.clear(); media.clear(); queuedBytes = 0; notifyAll();
        }
        try { link.close(); } catch (IOException ignored) {}
        deadlines.shutdownNow();
        if (reason != null) failure.stopped(reason);
    }
    @Override public void close() { stop(null); }
}
