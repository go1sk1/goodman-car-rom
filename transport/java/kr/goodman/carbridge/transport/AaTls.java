package kr.goodman.carbridge.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

/** TLS records travel inside AA frames. Certificate identity/trust must be provisioned by the caller. */
public final class AaTls implements AaWire.Cipher, AutoCloseable {
    private static final int LIMIT = 128 * 1024;
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
    private final SSLEngine engine;
    private final ByteBuffer incoming = ByteBuffer.allocate(LIMIT);
    private boolean ready, closed;

    public AaTls(SSLContext provisionedContext) throws SSLException {
        engine = java.util.Objects.requireNonNull(provisionedContext).createSSLEngine();
        engine.setUseClientMode(false); // Phone is the TLS server, including on a phone-initiated socket.
        engine.setNeedClientAuth(true);
        engine.setEnabledProtocols(new String[]{"TLSv1.2"});
        engine.beginHandshake();
    }
    public synchronized boolean ready() { return ready && !closed; }

    /** Returns handshake records for plaintext AA message 0x0003; never returns application data. */
    public synchronized List<byte[]> handshake(byte[] records) throws IOException {
        if (closed || ready) throw new SSLException("Unexpected TLS handshake state");
        if (records == null || records.length > incoming.remaining()) throw new SSLException("TLS handshake exceeds limit");
        incoming.put(records);
        List<byte[]> replies = new ArrayList<>();
        int generated = 0;
        try {
            for (int iteration = 0; iteration < 256; iteration++) {
                SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
                if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    Runnable task;
                    boolean ran = false;
                    while ((task = engine.getDelegatedTask()) != null) { task.run(); ran = true; }
                    if (!ran) throw new SSLException("TLS task made no progress");
                } else if (status == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    ByteBuffer output = ByteBuffer.allocate(LIMIT);
                    SSLEngineResult result = engine.wrap(EMPTY.duplicate(), output);
                    requireOk(result);
                    if (output.position() != 0) {
                        generated += output.position();
                        if (generated > LIMIT) throw new SSLException("TLS response exceeds limit");
                        replies.add(copy(output));
                    }
                    if (result.bytesProduced() == 0 && result.getHandshakeStatus() == status)
                        throw new SSLException("TLS wrap made no progress");
                } else if (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                    if (incoming.position() == 0) return replies;
                    incoming.flip();
                    ByteBuffer application = ByteBuffer.allocate(LIMIT);
                    SSLEngineResult result = engine.unwrap(incoming, application);
                    incoming.compact();
                    if (application.position() != 0) throw new SSLException("Application data before authentication");
                    if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) return replies;
                    requireOk(result);
                    if (result.bytesConsumed() == 0 && result.getHandshakeStatus() == status)
                        throw new SSLException("TLS unwrap made no progress");
                } else if (status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                    // The SSLContext's trust manager must accept an actual peer certificate chain.
                    if (engine.getSession().getPeerCertificates().length == 0 || incoming.position() != 0)
                        throw new SSLException("Invalid completed TLS session");
                    ready = true; return replies;
                } else throw new SSLException("Unsupported TLS handshake state");
            }
            throw new SSLException("Excessive TLS handshake transitions");
        } catch (IOException | RuntimeException error) { close(); throw error; }
    }
    @Override public synchronized byte[] encrypt(byte[] plaintext) throws IOException {
        requireReady();
        if (plaintext == null || plaintext.length == 0 || plaintext.length > 16384)
            throw new SSLException("Invalid TLS application fragment");
        ByteBuffer source = ByteBuffer.wrap(plaintext), destination = ByteBuffer.allocate(65535);
        try {
            while (source.hasRemaining()) {
                SSLEngineResult result = engine.wrap(source, destination);
                requireApplicationResult(result);
                if (result.bytesConsumed() == 0) throw new SSLException("TLS wrap stalled");
            }
            return copy(destination);
        } catch (IOException | RuntimeException error) { close(); throw error; }
    }
    @Override public synchronized byte[] decrypt(byte[] ciphertext) throws IOException {
        requireReady();
        if (ciphertext == null || ciphertext.length == 0 || ciphertext.length > 65535)
            throw new SSLException("Invalid TLS record size");
        ByteBuffer source = ByteBuffer.wrap(ciphertext), destination = ByteBuffer.allocate(65535);
        try {
            while (source.hasRemaining()) {
                SSLEngineResult result = engine.unwrap(source, destination);
                requireApplicationResult(result);
                if (result.bytesConsumed() == 0) throw new SSLException("TLS unwrap stalled");
            }
            return copy(destination);
        } catch (IOException | RuntimeException error) { close(); throw error; }
    }
    private void requireReady() throws SSLException {
        if (!ready()) throw new SSLException("TLS session is not ready");
    }
    private static void requireOk(SSLEngineResult result) throws SSLException {
        if (result.getStatus() != SSLEngineResult.Status.OK) throw new SSLException("TLS engine status: " + result.getStatus());
    }
    private static void requireApplicationResult(SSLEngineResult result) throws SSLException {
        requireOk(result);
        if (result.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
            throw new SSLException("TLS renegotiation is not supported during projection");
    }
    private static byte[] copy(ByteBuffer source) {
        source.flip(); byte[] bytes = new byte[source.remaining()]; source.get(bytes); return bytes;
    }
    @Override public synchronized void close() {
        closed = true; ready = false; incoming.clear(); engine.closeOutbound();
    }
}
