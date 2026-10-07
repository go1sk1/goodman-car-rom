package kr.goodman.carbridge.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Decode input channel message 0x8001 after channel binding and session authentication. */
public final class ButtonDecoder {
    public static final class Button {
        public final long headUnitTimestampUs;
        public final int code, metaState;
        public final boolean down, longPress;
        Button(long timestamp, int code, int meta, boolean down, boolean longPress) {
            headUnitTimestampUs = timestamp; this.code = code; metaState = meta;
            this.down = down; this.longPress = longPress;
        }
    }
    public static List<Button> decode(byte[] inputEvent) throws IOException {
        List<ProtoFields.Field> fields = ProtoFields.parse(inputEvent);
        long timestamp = ProtoFields.single(fields, 1, true).integer();
        if (timestamp < 0) throw new IOException("Invalid input timestamp");
        byte[] container = ProtoFields.bytes(fields, 4);
        if (container == null) return Collections.emptyList();
        List<Button> buttons = new ArrayList<>();
        for (ProtoFields.Field item : ProtoFields.parse(container)) if (item.number == 1) {
            if (buttons.size() == 64) throw new IOException("Excessive button batch");
            List<ProtoFields.Field> values = ProtoFields.parse(item.bytes());
            long code = ProtoFields.single(values, 1, true).integer();
            long down = ProtoFields.single(values, 2, true).integer();
            long meta = ProtoFields.single(values, 3, true).integer();
            long longPress = ProtoFields.integer(values, 4, 0);
            if (code < 0 || code > Integer.MAX_VALUE || meta < 0 || meta > 0xffffffffL
                    || down < 0 || down > 1 || longPress < 0 || longPress > 1)
                throw new IOException("Invalid button event");
            buttons.add(new Button(timestamp, (int) code, (int) meta, down == 1, longPress == 1));
        }
        return Collections.unmodifiableList(buttons);
    }
    private ButtonDecoder() {}
}
