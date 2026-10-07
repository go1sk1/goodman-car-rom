package kr.goodman.carbridge.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Vehicle LOCATION subscription. Units and measurement clock require a confirmed vehicle profile. */
public final class VehicleGnssChannel {
    public static final class RawFix {
        public final int latitude, longitude;
        public final Long accuracy, headUnitTimestamp;
        RawFix(int latitude, int longitude, Long accuracy, Long timestamp) {
            this.latitude = latitude; this.longitude = longitude; this.accuracy = accuracy; headUnitTimestamp = timestamp;
        }
    }
    public interface Listener {
        /** Reception time is not a substitute for the measurement's actual age. */
        void fixes(List<RawFix> fixes, long receivedMonotonicNanos);
        void unavailable(String reason);
    }
    private final AaSession session;
    private final ServiceCatalog.Channel channel;
    private final Listener listener;
    private int state; // 0=new, 1=opening, 2=subscribing, 3=subscribed, 4=closed
    public VehicleGnssChannel(AaSession session, ServiceCatalog.Channel channel, Listener listener) throws IOException {
        if (channel.kind != ServiceCatalog.Kind.SENSOR) throw new IOException("Not a vehicle sensor channel");
        boolean supported = false;
        for (ProtoFields.Field entry : ProtoFields.parse(channel.configuration())) if (entry.number == 1) {
            if (ProtoFields.single(ProtoFields.parse(entry.bytes()), 1, true).integer() == 1) supported = true;
        }
        if (!supported) throw new IOException("Vehicle did not advertise GPS location");
        this.session = java.util.Objects.requireNonNull(session); this.channel = channel;
        this.listener = java.util.Objects.requireNonNull(listener);
    }
    public synchronized void open() throws IOException {
        if (state != 0) throw new IOException("GPS channel already opened");
        send(true, 7, new ProtoFields.Writer().integer(1, 0).integer(2, channel.id).build()); state = 1;
    }
    public synchronized void receive(AaWire.Message message, long receivedMonotonicNanos) throws IOException {
        if (message.channel != channel.id || !message.encrypted || state == 4) return;
        List<ProtoFields.Field> fields = ProtoFields.parse(message.body());
        int type = message.type();
        if (type == 8 && message.control && state == 1) {
            if (ProtoFields.single(fields, 1, true).integer() != 0) { rejected("Vehicle rejected sensor channel"); return; }
            send(false, 0x8001, new ProtoFields.Writer().integer(1, 1).integer(2, 1000).build()); state = 2;
        } else if (type == 0x8002 && !message.control && state == 2) {
            if (ProtoFields.single(fields, 1, true).integer() != 0) { rejected("Vehicle GPS subscription unavailable"); return; }
            state = 3;
        } else if (type == 0x8003 && !message.control && state == 3) {
            List<RawFix> result = new ArrayList<>();
            for (ProtoFields.Field item : fields) if (item.number == 1) {
                if (result.size() >= 16) throw new IOException("Excessive vehicle GPS batch");
                List<ProtoFields.Field> fix = ProtoFields.parse(item.bytes());
                long latitude = ProtoFields.single(fix, 2, true).integer();
                long longitude = ProtoFields.single(fix, 3, true).integer();
                if (latitude < Integer.MIN_VALUE || latitude > 0xffffffffL
                        || longitude < Integer.MIN_VALUE || longitude > 0xffffffffL)
                    throw new IOException("GPS int32 field overflow");
                ProtoFields.Field accuracyField = ProtoFields.single(fix, 4, false);
                Long accuracy = accuracyField == null ? null : accuracyField.integer();
                if (accuracy != null && (accuracy < 0 || accuracy > 0xffffffffL)) throw new IOException("GPS accuracy overflow");
                // Legacy heads may supply field 1; later schema revisions omit it entirely.
                ProtoFields.Field timestampField = ProtoFields.single(fix, 1, false);
                Long timestamp = timestampField == null ? null : timestampField.integer();
                if (timestamp != null && timestamp < 0) throw new IOException("GPS timestamp overflow");
                result.add(new RawFix((int) latitude, (int) longitude, accuracy, timestamp));
            }
            if (!result.isEmpty()) listener.fixes(Collections.unmodifiableList(result), receivedMonotonicNanos);
        } else if (type == 0x8004 && !message.control && state == 3) {
            long sensor = ProtoFields.single(fields, 1, true).integer();
            long status = ProtoFields.single(fields, 2, true).integer();
            if (sensor == 1 && status != 0) listener.unavailable("Vehicle GPS signal unavailable");
            // Keep the subscription: the quality selector can reacquire after fresh accurate fixes return.
        }
    }
    private void rejected(String reason) { state = 4; listener.unavailable(reason); }
    private void send(boolean control, int type, byte[] body) throws IOException {
        if (!session.offer(channel.id, control, type, body)) throw new IOException("GPS control queue unavailable");
    }
    public synchronized void close() { state = 4; }
}
