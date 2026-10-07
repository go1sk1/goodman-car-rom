package kr.goodman.carbridge.transport;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Opens the input service and binds only keycodes advertised by this HU. */
public final class VehicleInputChannel {
    public interface Listener {
        void buttons(List<ButtonDecoder.Button> buttons);
        void inputEvent(byte[] event) throws IOException;
        void unavailable(String reason);
    }
    private final AaSession session;
    private final ServiceCatalog.Channel channel;
    private final Listener listener;
    private final Set<Integer> bound = new HashSet<>();
    private int state; // 0=new, 1=opening, 2=binding, 3=active, 4=closed
    public VehicleInputChannel(AaSession session, ServiceCatalog.Channel channel, Listener listener) {
        if (channel.kind != ServiceCatalog.Kind.INPUT) throw new IllegalArgumentException("Not an input channel");
        this.session = java.util.Objects.requireNonNull(session); this.channel = channel;
        this.listener = java.util.Objects.requireNonNull(listener);
    }
    public synchronized void open() throws IOException {
        if (state != 0) throw new IOException("Input channel already opened");
        List<Long> keys = ProtoFields.integers(ProtoFields.parse(channel.configuration()), 1);
        if (keys.size() > 512) throw new IOException("Too many advertised input keys");
        for (long key : keys) {
            if (key < 0 || key > Integer.MAX_VALUE) throw new IOException("Invalid advertised key");
            bound.add((int) key);
        }
        send(true, 7, new ProtoFields.Writer().integer(1, 0).integer(2, channel.id).build()); state = 1;
    }
    public synchronized void receive(AaWire.Message message) throws IOException {
        if (message.channel != channel.id || !message.encrypted || state == 4) return;
        if (message.type() == 8 && message.control && state == 1) {
            if (ProtoFields.single(ProtoFields.parse(message.body()), 1, true).integer() != 0) { rejected(); return; }
            ProtoFields.Writer request = new ProtoFields.Writer();
            for (int key : bound) request.integer(1, key);
            send(false, 0x8002, request.build()); state = 2;
        } else if (message.type() == 0x8003 && !message.control && state == 2) {
            if (ProtoFields.single(ProtoFields.parse(message.body()), 1, true).integer() != 0) { rejected(); return; }
            state = 3;
        } else if (message.type() == 0x8001 && !message.control && state == 3) {
            byte[] body = message.body();
            List<ButtonDecoder.Button> buttons = ButtonDecoder.decode(body);
            for (ButtonDecoder.Button button : buttons)
                if (!bound.contains(button.code)) throw new IOException("Vehicle sent an unbound key");
            if (!buttons.isEmpty()) listener.buttons(buttons);
            listener.inputEvent(body);
        }
    }
    private void rejected() { state = 4; listener.unavailable("Vehicle rejected input binding"); }
    private void send(boolean control, int type, byte[] data) throws IOException {
        if (!session.offer(channel.id, control, type, data)) throw new IOException("Input control queue unavailable");
    }
    public synchronized void close() { state = 4; bound.clear(); }
}
