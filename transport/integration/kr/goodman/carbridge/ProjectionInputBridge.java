package kr.goodman.carbridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import kr.goodman.carbridge.transport.ButtonDecoder;
import kr.goodman.carbridge.transport.TouchDecoder;
import kr.goodman.carbridge.transport.VehicleInputChannel;

/** One authenticated input-channel/session boundary. Close before replacing the runtime token. */
final class ProjectionInputBridge implements VehicleInputChannel.Listener, AutoCloseable {
    private static final int MAX_PENDING = 32;
    private static final long MAX_AGE_MS = 500;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VehicleRuntime runtime;
    private final Object token;
    private final VehicleTouchInjector touch;
    private final int width, height;
    private final Map<Integer, VehicleRuntime.Key> keyMap;
    private final Consumer<String> status;
    private final Set<Integer> held = new HashSet<>();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private boolean scheduled, resetPending, closed;
    private String failure;
    private boolean touchFocused;
    private int focusGeneration;
    private long lastButtonTimestamp = -1;

    private static final class Pending {
        final long arrival = SystemClock.uptimeMillis();
        final List<ButtonDecoder.Button> buttons;
        final TouchDecoder.Event touch;
        int focusGeneration;
        Pending(List<ButtonDecoder.Button> buttons, TouchDecoder.Event touch) {
            this.buttons = buttons; this.touch = touch;
        }
    }

    /** keyMap must come from the selected protocol profile, not guessed Android keycode values. */
    ProjectionInputBridge(Context context, VehicleRuntime runtime, Object token, int width, int height,
            Map<Integer, VehicleRuntime.Key> keyMap, Consumer<String> status) {
        requireMain();
        this.runtime = java.util.Objects.requireNonNull(runtime);
        this.token = java.util.Objects.requireNonNull(token);
        this.status = java.util.Objects.requireNonNull(status);
        if (keyMap.size() > 32) throw new IllegalArgumentException("Too many mapped action buttons");
        Map<Integer, VehicleRuntime.Key> copy = new HashMap<>();
        for (Map.Entry<Integer, VehicleRuntime.Key> entry : keyMap.entrySet()) {
            if (entry.getKey() < 0 || entry.getValue() == null) throw new IllegalArgumentException("Invalid key mapping");
            copy.put(entry.getKey(), entry.getValue());
        }
        this.keyMap = Collections.unmodifiableMap(copy); this.width = width; this.height = height;
        touch = new VehicleTouchInjector(context, width, height);
    }

    void setTouchFocus(boolean allowed) {
        requireMain();
        synchronized (this) {
            if (closed) return;
            if (touchFocused != allowed) focusGeneration++;
            touchFocused = allowed;
        }
        touch.setFocused(allowed);
    }

    @Override public void buttons(List<ButtonDecoder.Button> buttons) {
        if (buttons.size() > 64) { unavailable("차량 버튼 데이터가 너무 많습니다"); return; }
        enqueue(new Pending(new ArrayList<>(buttons), null));
    }

    @Override public void inputEvent(byte[] event) throws IOException {
        if (event.length > 65536) throw new IOException("Input event is too large");
        TouchDecoder.Event decoded = TouchDecoder.decode(event, width, height);
        if (decoded != null) enqueue(new Pending(null, decoded));
    }

    private synchronized void enqueue(Pending event) {
        if (closed || failure != null) return;
        if (event.touch != null) {
            if (!touchFocused) return;
            event.focusGeneration = focusGeneration;
        }
        if (queue.size() >= MAX_PENDING) {
            queue.clear(); resetPending = true;
            // A dropped release must not trigger an action or leave an injected pointer down.
        } else queue.addLast(event);
        schedule();
    }

    private void schedule() {
        if (!scheduled) { scheduled = true; main.post(this::drain); }
    }

    @Override public synchronized void unavailable(String reason) {
        if (closed || failure != null) return;
        failure = reason; queue.clear(); resetPending = true; schedule();
    }

    private void drain() {
        requireMain();
        // At most one bounded batch per main-loop turn; the reader cannot monopolise the UI.
        List<Pending> batch; boolean reset; String problem;
        synchronized (this) {
            scheduled = false;
            if (closed) return;
            reset = resetPending; resetPending = false; problem = failure;
            batch = new ArrayList<>(queue); queue.clear();
        }
        if (reset) cancelInput();
        if (problem != null) { touch.setFocused(false); status.accept(problem); return; }
        for (Pending pending : batch) {
            if (SystemClock.uptimeMillis() - pending.arrival > MAX_AGE_MS) { cancelInput(); continue; }
            try {
                if (pending.touch != null && pending.focusGeneration == focusGeneration)
                    touch.accept(pending.touch);
                if (pending.buttons != null) for (ButtonDecoder.Button button : pending.buttons) dispatch(button);
            } catch (IOException | SecurityException invalid) {
                cancelInput(); status.accept("차량 입력을 중단했습니다: " + invalid.getMessage());
                // Disable this channel until a new authenticated session is established.
                synchronized (this) { failure = "차량 입력 채널 재연결 필요"; queue.clear(); }
                touch.setFocused(false); return;
            }
        }
    }

    private void dispatch(ButtonDecoder.Button button) throws IOException {
        if (button.headUnitTimestampUs < lastButtonTimestamp) throw new IOException("버튼 수신 순서 오류");
        lastButtonTimestamp = button.headUnitTimestampUs;
        VehicleRuntime.Key logical = keyMap.getOrDefault(button.code, VehicleRuntime.Key.OTHER);
        boolean repeat = button.longPress || held.contains(button.code);
        if (logical != VehicleRuntime.Key.OTHER) {
            if (button.down) held.add(button.code); else held.remove(button.code);
        }
        // Unknown keys are visible in diagnostics but never injected or used for calls.
        runtime.onVehicleKey(token, button.code, logical, button.down, repeat, false);
    }

    private void cancelInput() {
        touch.cancel();
        for (int code : held)
            runtime.onVehicleKey(token, code, keyMap.get(code), false, false, true);
        held.clear();
    }

    @Override public void close() {
        requireMain();
        synchronized (this) { if (closed) return; closed = true; queue.clear(); }
        cancelInput(); touch.close();
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Input control requires main thread");
    }
}
