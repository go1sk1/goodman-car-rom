package kr.goodman.carbridge.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small bounded protobuf wire reader. Message semantics stay in the channel decoders. */
final class ProtoFields {
    private static final int MAX_BYTES = 1024 * 1024, MAX_FIELDS = 4096;
    static final class Field {
        final int number, wire;
        final long value;
        private final byte[] bytes;
        Field(int number, int wire, long value, byte[] bytes) {
            this.number = number; this.wire = wire; this.value = value; this.bytes = bytes;
        }
        long integer() throws IOException {
            if (wire != 0) throw new IOException("Expected protobuf varint");
            return value;
        }
        byte[] bytes() throws IOException {
            if (wire != 2) throw new IOException("Expected protobuf byte string");
            return bytes.clone();
        }
    }
    private static final class Cursor {
        final byte[] bytes; int position;
        Cursor(byte[] bytes) { this.bytes = bytes; }
        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 70; shift += 7) {
                if (position == bytes.length) throw new IOException("Truncated protobuf varint");
                int next = bytes[position++] & 255;
                if (shift == 63 && (next & 254) != 0) throw new IOException("Protobuf varint overflow");
                value |= (long) (next & 127) << shift;
                if ((next & 128) == 0) return value;
            }
            throw new IOException("Excessive protobuf varint");
        }
        byte[] take(int length) throws IOException {
            if (length < 0 || length > bytes.length - position) throw new IOException("Truncated protobuf field");
            byte[] value = java.util.Arrays.copyOfRange(bytes, position, position + length);
            position += length; return value;
        }
    }
    static List<Field> parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("Protobuf message exceeds limit");
        Cursor input = new Cursor(bytes); List<Field> result = new ArrayList<>();
        while (input.position < bytes.length) {
            if (result.size() == MAX_FIELDS) throw new IOException("Too many protobuf fields");
            long tag = input.varint();
            if (tag <= 0 || tag > 0xffffffffL || (tag >>> 3) == 0) throw new IOException("Invalid protobuf tag");
            int number = (int) (tag >>> 3), wire = (int) tag & 7;
            long value = 0; byte[] data = null;
            switch (wire) {
                case 0: value = input.varint(); break;
                case 1: data = input.take(8); break;
                case 2:
                    long length = input.varint();
                    if (length < 0 || length > MAX_BYTES) throw new IOException("Invalid protobuf field length");
                    data = input.take((int) length); break;
                case 5: data = input.take(4); break;
                default: throw new IOException("Unsupported protobuf wire type");
            }
            result.add(new Field(number, wire, value, data));
        }
        return Collections.unmodifiableList(result);
    }
    static Field single(List<Field> fields, int number, boolean required) throws IOException {
        Field found = null;
        for (Field field : fields) if (field.number == number) {
            if (found != null) throw new IOException("Duplicate singular protobuf field");
            found = field;
        }
        if (required && found == null) throw new IOException("Missing required protobuf field");
        return found;
    }
    static long integer(List<Field> fields, int number, long fallback) throws IOException {
        Field found = single(fields, number, false); return found == null ? fallback : found.integer();
    }
    static byte[] bytes(List<Field> fields, int number) throws IOException {
        Field found = single(fields, number, false); return found == null ? null : found.bytes();
    }
    static List<Long> integers(List<Field> fields, int number) throws IOException {
        List<Long> result = new ArrayList<>();
        for (Field field : fields) if (field.number == number) {
            if (field.wire == 0) result.add(field.integer());
            else {
                Cursor packed = new Cursor(field.bytes());
                while (packed.position < packed.bytes.length) {
                    if (result.size() >= MAX_FIELDS) throw new IOException("Too many packed integers");
                    result.add(packed.varint());
                }
            }
        }
        return result;
    }
    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private void varint(long value) {
            do {
                int next = (int) value & 127; value >>>= 7;
                out.write(value == 0 ? next : next | 128);
            } while (value != 0);
        }
        private void tag(int number, int wire) {
            if (number < 1 || number > 0x1fffffff) throw new IllegalArgumentException("Invalid field number");
            varint(((long) number << 3) | wire);
        }
        Writer integer(int field, long value) {
            if (out.size() > MAX_BYTES - 15) throw new IllegalStateException("Protobuf output exceeds limit");
            tag(field, 0); varint(value); return this;
        }
        Writer bytes(int field, byte[] value) {
            if (value == null || value.length > MAX_BYTES || out.size() > MAX_BYTES - value.length - 10)
                throw new IllegalArgumentException("Protobuf output exceeds limit");
            tag(field, 2); varint(value.length); out.write(value, 0, value.length); return this;
        }
        Writer text(int field, String value) { return bytes(field, value.getBytes(StandardCharsets.UTF_8)); }
        byte[] build() {
            if (out.size() > MAX_BYTES) throw new IllegalStateException("Protobuf output exceeds limit");
            return out.toByteArray();
        }
    }
    private ProtoFields() {}
}
