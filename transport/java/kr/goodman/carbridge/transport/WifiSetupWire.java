package kr.goodman.carbridge.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Wireless setup has a different header from projection; credentials are never logged here. */
public final class WifiSetupWire {
    private static final int MAX_PAYLOAD = 16384;
    public static final class Packet {
        public final int type;
        private final byte[] payload;
        public Packet(int type, byte[] payload) {
            if (type < 1 || type > 65535 || payload == null || payload.length > MAX_PAYLOAD)
                throw new IllegalArgumentException("Invalid wireless setup packet");
            this.type = type; this.payload = payload.clone();
        }
        public byte[] payload() { return payload.clone(); }
    }
    public static Packet read(InputStream input) throws IOException {
        int high = input.read();
        if (high < 0) return null;
        byte[] header = AaWire.bytes(input, 3);
        int length = (high << 8) | (header[0] & 255);
        int type = ((header[1] & 255) << 8) | (header[2] & 255);
        if (length > MAX_PAYLOAD || type == 0) throw new IOException("Invalid wireless setup frame");
        return new Packet(type, AaWire.bytes(input, length));
    }
    public static void write(OutputStream output, Packet packet) throws IOException {
        byte[] frame = new byte[packet.payload.length + 4];
        frame[0] = (byte) (packet.payload.length >>> 8); frame[1] = (byte) packet.payload.length;
        frame[2] = (byte) (packet.type >>> 8); frame[3] = (byte) packet.type;
        System.arraycopy(packet.payload, 0, frame, 4, packet.payload.length);
        output.write(frame); output.flush();
    }
    private WifiSetupWire() {}
}
