package kr.goodman.carbridge.transport;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/** Android Auto framing shared by USB AOA and the established wireless projection stream. */
public final class AaWire {
    public static final int MAX_MESSAGE = 4 * 1024 * 1024;
    private static final int MAX_PENDING = 8 * 1024 * 1024;
    private static final int FRAGMENT = 16 * 1024;
    private static final int FIRST = 1, LAST = 2, BULK = 3;
    private static final int CONTROL = 4, ENCRYPTED = 8;

    /** The session supplies its authenticated TLS engine. No plaintext fallback is allowed. */
    public interface Cipher {
        byte[] encrypt(byte[] plaintext) throws IOException;
        byte[] decrypt(byte[] ciphertext) throws IOException;
    }
    public static final class Message {
        public final int channel;
        public final boolean control, encrypted;
        private final byte[] payload;
        public Message(int channel, boolean control, boolean encrypted, int type, byte[] body) {
            if (channel < 0 || channel > 255 || type < 0 || type > 65535
                    || body == null || body.length > MAX_MESSAGE - 2) throw new IllegalArgumentException();
            this.channel = channel; this.control = control; this.encrypted = encrypted;
            payload = new byte[body.length + 2];
            payload[0] = (byte) (type >>> 8); payload[1] = (byte) type;
            System.arraycopy(body, 0, payload, 2, body.length);
        }
        private Message(int channel, int flags, byte[] payload) throws IOException {
            if (payload.length < 2) throw new IOException("Missing message ID");
            this.channel = channel; control = (flags & CONTROL) != 0;
            encrypted = (flags & ENCRYPTED) != 0; this.payload = payload;
        }
        public int type() { return ((payload[0] & 255) << 8) | (payload[1] & 255); }
        public byte[] body() { return Arrays.copyOfRange(payload, 2, payload.length); }
        public int size() { return payload.length; }
    }
    private static final class Pending {
        final byte[] payload;
        final int flags;
        int length;
        Pending(int size, int flags) { payload = new byte[size]; this.flags = flags; }
    }

    /** One reader per connection. Wire length is ciphertext size; total size is plaintext size. */
    public static final class Reader {
        private final InputStream input;
        private final Pending[] pending = new Pending[256];
        private int reserved;
        private boolean failed;
        public Reader(InputStream input) { this.input = java.util.Objects.requireNonNull(input); }
        public Message read(Cipher cipher) throws IOException {
            if (failed) throw new IOException("Reader closed after protocol error");
            try {
                while (true) {
                    int channel = input.read();
                    if (channel < 0) {
                        if (reserved != 0) throw new EOFException("EOF during fragmented message");
                        return null;
                    }
                    byte[] header = bytes(input, 3);
                    int flags = header[0] & 255, part = flags & 3;
                    if ((flags & ~15) != 0) throw new IOException("Unknown frame flags");
                    int size = ((header[1] & 255) << 8) | (header[2] & 255);
                    if (size == 0) throw new IOException("Empty frame");
                    int total = 0;
                    if (part == FIRST) {
                        byte[] extended = bytes(input, 4);
                        long value = 0;
                        for (byte b : extended) value = (value << 8) | (b & 255);
                        if (value < 2 || value > MAX_MESSAGE) throw new IOException("Message size exceeds limit");
                        total = (int) value;
                    }
                    int attributes = flags & (CONTROL | ENCRYPTED);
                    byte[] payload = bytes(input, size);
                    if ((flags & ENCRYPTED) != 0) {
                        if (cipher == null) throw new IOException("Encrypted frame before TLS is ready");
                        payload = cipher.decrypt(payload);
                    }
                    if (payload == null || payload.length == 0 || payload.length > 65535)
                        throw new IOException("Invalid decrypted frame length");
                    Pending assembly = pending[channel];
                    if (part == BULK) {
                        if (assembly != null) throw new IOException("Bulk frame interrupts a fragmented message");
                        return new Message(channel, flags, payload);
                    }
                    if (part == FIRST) {
                        if (assembly != null || total <= payload.length || reserved > MAX_PENDING - total)
                            throw new IOException("Invalid or excessive fragment reservation");
                        assembly = new Pending(total, attributes);
                        pending[channel] = assembly; reserved += total;
                    } else if (assembly == null) throw new IOException("Continuation without first fragment");
                    if (assembly.flags != attributes || payload.length > assembly.payload.length - assembly.length)
                        throw new IOException("Fragment attributes or length mismatch");
                    System.arraycopy(payload, 0, assembly.payload, assembly.length, payload.length);
                    assembly.length += payload.length;
                    if (part == LAST) {
                        if (assembly.length != assembly.payload.length) throw new IOException("Truncated message");
                        pending[channel] = null; reserved -= assembly.payload.length;
                        return new Message(channel, flags, assembly.payload);
                    }
                    if (assembly.length == assembly.payload.length) throw new IOException("Missing last-fragment flag");
                }
            } catch (IOException | RuntimeException failure) {
                failed = true; Arrays.fill(pending, null); reserved = 0; throw failure;
            }
        }
    }

    /** Called by one writer; frames of one outgoing message are kept together. */
    public static void write(OutputStream output, Message message, Cipher cipher) throws IOException {
        if (message.encrypted && cipher == null) throw new IOException("TLS is not ready");
        int attributes = (message.control ? CONTROL : 0) | (message.encrypted ? ENCRYPTED : 0);
        int total = message.payload.length;
        for (int offset = 0; offset < total; offset += FRAGMENT) {
            int count = Math.min(FRAGMENT, total - offset);
            int part = total <= FRAGMENT ? BULK : offset == 0 ? FIRST : offset + count == total ? LAST : 0;
            byte[] payload = Arrays.copyOfRange(message.payload, offset, offset + count);
            if (message.encrypted) payload = cipher.encrypt(payload);
            if (payload == null || payload.length == 0 || payload.length > 65535)
                throw new IOException("Encrypted frame exceeds wire size");
            ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 8);
            frame.write(message.channel); frame.write(attributes | part);
            frame.write(payload.length >>> 8); frame.write(payload.length);
            if (part == FIRST) {
                frame.write(total >>> 24); frame.write(total >>> 16); frame.write(total >>> 8); frame.write(total);
            }
            frame.write(payload); output.write(frame.toByteArray());
        }
        output.flush();
    }
    static byte[] bytes(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) throw new EOFException("Truncated frame");
            if (count == 0) throw new IOException("Input stream made no progress");
            offset += count;
        }
        return result;
    }
    private AaWire() {}
}
