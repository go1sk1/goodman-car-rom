package kr.goodman.carbridge.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Touchscreen input only; touchpad/rotary data is not silently treated as a touchscreen. */
public final class TouchDecoder {
    public static final class Point {
        public final int id, x, y;
        Point(int id, int x, int y) { this.id = id; this.x = x; this.y = y; }
    }
    public static final class Event {
        public final long headUnitTimestampUs;
        public final int action, actionIndex;
        public final List<Point> points;
        Event(long timestamp, int action, int index, List<Point> points) {
            headUnitTimestampUs = timestamp; this.action = action; actionIndex = index;
            this.points = Collections.unmodifiableList(points);
        }
    }
    /** Coordinates must be bounded by the negotiated input surface, not guessed car dimensions. */
    public static Event decode(byte[] inputEvent, int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > 8192 || height > 8192) throw new IOException("Invalid input surface");
        List<ProtoFields.Field> outer = ProtoFields.parse(inputEvent);
        long timestamp = ProtoFields.single(outer, 1, true).integer();
        if (timestamp < 0) throw new IOException("Invalid input timestamp");
        byte[] bytes = ProtoFields.bytes(outer, 3);
        if (bytes == null) return null;
        List<ProtoFields.Field> touch = ProtoFields.parse(bytes);
        long action = ProtoFields.integer(touch, 3, 0), index = ProtoFields.integer(touch, 2, 0);
        if (action != 0 && action != 1 && action != 2 && action != 5 && action != 6)
            throw new IOException("Unsupported touch action");
        List<Point> points = new ArrayList<>(); Set<Integer> ids = new HashSet<>();
        for (ProtoFields.Field field : touch) if (field.number == 1) {
            if (points.size() >= 10) throw new IOException("Too many touch pointers");
            List<ProtoFields.Field> point = ProtoFields.parse(field.bytes());
            long x = ProtoFields.single(point, 1, true).integer();
            long y = ProtoFields.single(point, 2, true).integer();
            long id = ProtoFields.single(point, 3, true).integer();
            if (x < 0 || y < 0 || x >= width || y >= height || id < 0 || id > 31 || !ids.add((int) id))
                throw new IOException("Invalid touch pointer");
            points.add(new Point((int) id, (int) x, (int) y));
        }
        if (points.isEmpty() || index < 0 || index >= points.size()) throw new IOException("Invalid touch action index");
        return new Event(timestamp, (int) action, (int) index, points);
    }

    /** Run before Android injection. On rejection/disconnect, cancel the injected gesture and reset. */
    public static final class Gesture {
        private final Set<Integer> active = new HashSet<>();
        private long lastTimestamp = -1;
        public void accept(Event event) throws IOException {
            if (event == null || event.headUnitTimestampUs < lastTimestamp) throw new IOException("Out-of-order touch event");
            Set<Integer> next = new HashSet<>(); for (Point point : event.points) next.add(point.id);
            int changed = event.points.get(event.actionIndex).id;
            switch (event.action) {
                case 0:
                    if (!active.isEmpty() || next.size() != 1) throw new IOException("Unexpected touch down");
                    break;
                case 1:
                    if (next.size() != 1 || !active.equals(next)) throw new IOException("Unexpected touch up");
                    next.clear(); break;
                case 2:
                    if (active.isEmpty() || !active.equals(next)) throw new IOException("Touch move changed pointer IDs");
                    break;
                case 5:
                    if (active.isEmpty() || active.contains(changed) || next.size() != active.size() + 1
                            || !next.containsAll(active)) throw new IOException("Invalid additional pointer");
                    break;
                case 6:
                    if (active.size() < 2 || !active.equals(next)) throw new IOException("Invalid pointer release");
                    next.remove(changed); break;
                default: throw new IOException("Unsupported gesture action");
            }
            active.clear(); active.addAll(next); lastTimestamp = event.headUnitTimestampUs;
        }
        public boolean active() { return !active.isEmpty(); }
        public void reset() { active.clear(); lastTimestamp = -1; }
    }
    private TouchDecoder() {}
}
