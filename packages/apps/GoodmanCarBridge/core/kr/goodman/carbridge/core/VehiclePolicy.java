package kr.goodman.carbridge.core;

/** Transport-independent policy. Times are monotonic milliseconds, never wall clock. */
public final class VehiclePolicy {
    private VehiclePolicy() {}

    public enum Sound { MEDIA, GUIDANCE, SYSTEM, CALL }
    public enum Category {
        MUSIC_VIDEO(1, Sound.MEDIA), GAME(14, Sound.MEDIA),
        NAVIGATION(12, Sound.GUIDANCE), ASSISTANT(16, Sound.GUIDANCE),
        CALL(2, Sound.CALL);
        public final int androidUsage;
        public final Sound defaultSound;
        Category(int usage, Sound sound) { androidUsage = usage; defaultSound = sound; }
    }

    public static final class GpsSelector {
        public static final long MAX_AGE_MS = 5000;
        public static final float ACQUIRE_ACCURACY_M = 30;
        public static final float DROP_ACCURACY_M = 60;
        private long lastFix = -1;
        private int goodFixes;
        private boolean vehicle;

        public boolean offer(long fixTime, long now, float accuracy) {
            expire(now);
            if (fixTime < 0 || fixTime > now || now - fixTime > MAX_AGE_MS
                    || fixTime <= lastFix || !Float.isFinite(accuracy) || accuracy <= 0) return false;
            if (lastFix < 0 || fixTime - lastFix > 2500) goodFixes = 0;
            lastFix = fixTime;
            if (accuracy > DROP_ACCURACY_M) { vehicle = false; goodFixes = 0; return false; }
            if (!vehicle) {
                goodFixes = accuracy <= ACQUIRE_ACCURACY_M ? goodFixes + 1 : 0;
                vehicle = goodFixes >= 3;
            }
            return vehicle;
        }

        public boolean useVehicle(long now) { expire(now); return vehicle; }
        public long staleDeadlineMillis() { return lastFix < 0 ? 0 : lastFix + MAX_AGE_MS + 1; }
        private void expire(long now) {
            if (lastFix >= 0 && (now < lastFix || now - lastFix > MAX_AGE_MS)) reset();
        }
        public void reset() { lastFix = -1; goodFixes = 0; vehicle = false; }
    }

    public enum ButtonAction { CALL_TOGGLE, ANSWER, END_CALL, ASSISTANT, NONE }

    /** A press triggers on release once. A lost release cannot arm a later session. */
    public static final class ButtonPress {
        private long downAt = -1;
        private long lastRelease = -1000;
        private ButtonAction armed = ButtonAction.NONE;
        public void down(long now, boolean repeated, ButtonAction action) {
            if (repeated || downAt >= 0 || now < 0) return;
            downAt = now;
            armed = action;
        }
        public ButtonAction up(long now, boolean cancelled) {
            long started = downAt;
            ButtonAction action = armed;
            cancel();
            if (cancelled || started < 0 || now < started || now - started > 10000
                    || now - lastRelease < 250) return ButtonAction.NONE;
            lastRelease = now;
            return action;
        }
        public void cancel() { downAt = -1; armed = ButtonAction.NONE; }
    }
}
