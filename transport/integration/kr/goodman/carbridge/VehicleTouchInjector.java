package kr.goodman.carbridge;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import kr.goodman.carbridge.transport.TouchDecoder;

/** Main-thread input adapter for a negotiated, fit-centred mirror of the default display. */
final class VehicleTouchInjector implements AutoCloseable {
    private final Context context;
    private final InputManager input;
    private final DisplayManager displays;
    private final PowerManager power;
    private final KeyguardManager keyguard;
    private final int videoWidth, videoHeight;
    private final TouchDecoder.Gesture gesture = new TouchDecoder.Gesture();
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<TouchDecoder.Point> pointers = new ArrayList<>();
    private int phoneWidth, phoneHeight, rotation;
    private float scale, left, top;
    private long downTime, lastEventTime;
    private boolean focused, closed, ignoring;
    private final Runnable screenWatch = new Runnable() {
        @Override public void run() {
            if (!gesture.active() || closed) return;
            Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
            Point size = new Point();
            if (display != null) display.getRealSize(size);
            if (!focused || !power.isInteractive() || keyguard.isKeyguardLocked() || keyguard.isDeviceLocked()
                    || display == null || display.getRotation() != rotation
                    || size.x != phoneWidth || size.y != phoneHeight) { cancel(); return; }
            main.postDelayed(this, 250);
        }
    };

    VehicleTouchInjector(Context context, int videoWidth, int videoHeight) {
        requireMain();
        if (videoWidth < 2 || videoHeight < 2 || videoWidth > 3840 || videoHeight > 2160)
            throw new IllegalArgumentException("Invalid negotiated mirror size");
        this.context = context;
        this.videoWidth = videoWidth; this.videoHeight = videoHeight;
        input = context.getSystemService(InputManager.class);
        displays = context.getSystemService(DisplayManager.class);
        power = context.getSystemService(PowerManager.class);
        keyguard = context.getSystemService(KeyguardManager.class);
    }

    void setFocused(boolean allowed) {
        requireMain();
        focused = allowed && !closed;
        if (!focused) cancel();
    }

    void accept(TouchDecoder.Event event) throws IOException {
        requireMain();
        if (event == null || closed) return;
        if (!focused || !power.isInteractive() || keyguard.isKeyguardLocked()
                || keyguard.isDeviceLocked()) { cancel(); return; }
        if (context.checkSelfPermission(Manifest.permission.INJECT_EVENTS) != PackageManager.PERMISSION_GRANTED) {
            cancel(); throw new IOException("차량 터치 입력 권한이 없습니다");
        }
        Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) { cancel(); throw new IOException("미러링할 화면이 없습니다"); }
        Point size = new Point(); display.getRealSize(size);
        if (size.x <= 0 || size.y <= 0) { cancel(); return; }

        if (event.action == MotionEvent.ACTION_DOWN) {
            // A fresh DOWN is the only way to recover from a cancelled/rejected remote gesture.
            cancel(); ignoring = false;
            phoneWidth = size.x; phoneHeight = size.y; rotation = display.getRotation();
            scale = Math.min((float) videoWidth / phoneWidth, (float) videoHeight / phoneHeight);
            left = (videoWidth - phoneWidth * scale) / 2f;
            top = (videoHeight - phoneHeight * scale) / 2f;
            TouchDecoder.Point first = event.points.get(0);
            if (first.x < left || first.x >= left + phoneWidth * scale
                    || first.y < top || first.y >= top + phoneHeight * scale) {
                ignoring = true; return; // Black margins must not tap the edge of an app.
            }
            downTime = SystemClock.uptimeMillis(); lastEventTime = downTime;
        } else if (ignoring || !gesture.active()) return;
        else if (size.x != phoneWidth || size.y != phoneHeight || display.getRotation() != rotation) {
            cancel(); return;
        }

        try { gesture.accept(event); }
        catch (IOException malformed) { cancel(); throw malformed; }
        int action = event.action;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action |= event.actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        try {
            inject(action, event.points);
            pointers = new ArrayList<>(event.points);
            if (event.action == MotionEvent.ACTION_POINTER_UP) pointers.remove(event.actionIndex);
            if (event.action == MotionEvent.ACTION_UP) pointers.clear();
            main.removeCallbacks(screenWatch);
            if (gesture.active()) main.postDelayed(screenWatch, 250);
        } catch (IOException failure) { cancel(); throw failure; }
    }

    private void inject(int action, List<TouchDecoder.Point> points) throws IOException {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[points.size()];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[points.size()];
        for (int i = 0; i < points.size(); i++) {
            TouchDecoder.Point point = points.get(i);
            MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
            property.id = point.id; property.toolType = MotionEvent.TOOL_TYPE_FINGER;
            properties[i] = property;
            MotionEvent.PointerCoords coordinate = new MotionEvent.PointerCoords();
            coordinate.x = Math.max(0, Math.min(phoneWidth - 1, (point.x - left) / scale));
            coordinate.y = Math.max(0, Math.min(phoneHeight - 1, (point.y - top) / scale));
            coordinate.pressure = action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP ? 0 : 1;
            coordinate.size = 1; coordinates[i] = coordinate;
        }
        // Remote timestamps order gestures, but are never used as Android uptime.
        long now = Math.max(lastEventTime, SystemClock.uptimeMillis()); lastEventTime = now;
        MotionEvent motion = MotionEvent.obtain(downTime, now, action, points.size(), properties, coordinates,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        motion.setDisplayId(Display.DEFAULT_DISPLAY);
        try {
            // Async avoids blocking the main thread on another application's event handling.
            // Acceptance here is not evidence that the target application handled the event.
            if (!input.injectInputEvent(motion, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC))
                throw new IOException("Android가 차량 터치 입력을 받지 못했습니다");
        } catch (SecurityException denied) { throw new IOException("차량 터치 입력 권한이 거부됐습니다", denied); }
        finally { motion.recycle(); }
    }

    void cancel() {
        requireMain();
        main.removeCallbacks(screenWatch);
        if (!pointers.isEmpty()) {
            try { inject(MotionEvent.ACTION_CANCEL, pointers); }
            catch (IOException ignored) { /* Local state still needs clearing after permission loss. */ }
        }
        pointers.clear(); gesture.reset(); ignoring = true;
    }

    @Override public void close() { requireMain(); cancel(); focused = false; closed = true; }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Input requires main thread");
    }
}
