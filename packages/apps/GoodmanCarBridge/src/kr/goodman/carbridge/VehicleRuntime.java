package kr.goodman.carbridge;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.*;
import android.view.KeyEvent;
import java.util.*;
import kr.goodman.carbridge.core.VehiclePolicy;
import kr.goodman.carbridge.core.VehiclePolicy.ButtonAction;

/** Session boundary for decoded, authenticated vehicle data. All control callbacks use main thread. */
final class VehicleRuntime implements AutoCloseable {
    enum Key { CALL, END_CALL, VOICE, OTHER }
    private final Context context;
    private final HfpController hfp;
    private final VehicleSettings settings;
    private final VehicleAudioBridge audio;
    private final LocationManager location;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final LinkedHashMap<String, VehiclePolicy.ButtonPress> presses = new LinkedHashMap<>();
    private final VehiclePolicy.GpsSelector gps = new VehiclePolicy.GpsSelector();
    private Object session;
    private VehicleTransport transport;
    private String gpsSession;
    private boolean gpsStarted;
    private String lastAction = "차량 연결 대기";
    private String gpsState = "위치: 폰 GPS";
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = this::onPreferencesChanged;
    private void onPreferencesChanged(SharedPreferences prefs, String key) {
        if (!settings.monitor()) events.clear();
        for (VehiclePolicy.ButtonPress press : presses.values()) press.cancel();
        if (transport != null) {
            if (key != null && (key.startsWith("sound.") || key.equals("vehicle_microphone")))
                audio.configure(session, transport, settings);
            if ("vehicle_gps".equals(key)) { endGps(); beginGps(); }
        }
    }
    VehicleRuntime(Context context, HfpController hfp) {
        this.context = context; this.hfp = hfp; settings = new VehicleSettings(context);
        location = context.getSystemService(LocationManager.class); audio = new VehicleAudioBridge(context);
        settings.preferences.registerOnSharedPreferenceChangeListener(changed);
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Vehicle control must use main thread");
    }
    Object openSession(VehicleTransport endpoint) {
        requireMain(); disconnect(session);
        transport = Objects.requireNonNull(endpoint); session = new Object();
        gpsSession = UUID.randomUUID().toString();
        audio.configure(session, endpoint, settings); beginGps();
        main.post(callAudioWatch);
        lastAction = "차량 연결됨"; return session;
    }
    void refreshTransport(Object token) {
        requireMain();
        if (token != session || session == null || transport == null) return;
        audio.configure(session, transport, settings);
    }
    void disconnect(Object token) {
        requireMain(); if (token != session) return;
        endGps(); session = null; transport = null; gpsSession = null;
        main.removeCallbacks(callAudioWatch);
        presses.clear(); gps.reset(); audio.configure(null, null, settings);
        lastAction = "차량 연결 대기"; gpsState = "위치: 폰 GPS";
    }
    void onVehicleKey(Object token, int rawCode, Key logical, boolean down, boolean repeat, boolean cancelled) {
        requireMain(); if (session == null || token != session) return;
        handleKey("차량", rawCode, logical, down, repeat, cancelled);
    }
    boolean onAndroidKey(KeyEvent event) {
        requireMain();
        int code = event.getKeyCode();
        Key key = code == KeyEvent.KEYCODE_CALL || code == KeyEvent.KEYCODE_HEADSETHOOK ? Key.CALL
                : code == KeyEvent.KEYCODE_ENDCALL ? Key.END_CALL
                : code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_ASSIST ? Key.VOICE : Key.OTHER;
        boolean diagnostic = key != Key.OTHER || code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        if (diagnostic && (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP))
            handleKey("Android", code, key, event.getAction() == KeyEvent.ACTION_DOWN,
                    event.getRepeatCount() > 0, event.isCanceled());
        // Consume both halves of mapped keys to avoid a second action in the phone dialer.
        return key != Key.OTHER;
    }
    private ButtonAction action(Key key) {
        if (key == Key.CALL) return settings.action(false);
        if (key == Key.END_CALL) return settings.action(false) == ButtonAction.NONE ? ButtonAction.NONE : ButtonAction.END_CALL;
        if (key == Key.VOICE) return settings.action(true);
        return ButtonAction.NONE;
    }
    private void handleKey(String source, int rawCode, Key logical, boolean down, boolean repeat, boolean cancelled) {
        long now = SystemClock.elapsedRealtime();
        if (settings.monitor()) {
            events.addFirst(source + " · " + logical + " · 코드 " + rawCode + " · "
                    + (cancelled ? "취소" : down ? repeat ? "길게 누름" : "눌림" : "뗌"));
            while (events.size() > 40) events.removeLast();
        }
        if (logical == Key.OTHER) return;
        String identity = source + ":" + rawCode;
        VehiclePolicy.ButtonPress press = presses.get(identity);
        if (press == null) {
            if (!down) return;
            if (presses.size() >= 32) presses.remove(presses.keySet().iterator().next());
            press = new VehiclePolicy.ButtonPress(); presses.put(identity, press);
        }
        if (down) press.down(now, repeat, action(logical));
        else perform(press.up(now, cancelled));
    }
    private void perform(ButtonAction action) {
        try {
            switch (action) {
                case CALL_TOGGLE:
                    if (!hfp.handleCallButton()) context.startActivity(new Intent(context, CarActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case ANSWER: hfp.answer(); break;
                case END_CALL: hfp.hangUpAll(); break;
                case ASSISTANT:
                    AssistantLauncher.launch(context);
                    break;
                case NONE: return;
            }
            lastAction = "버튼 동작 요청 전달";
        } catch (SecurityException | ActivityNotFoundException unavailable) { lastAction = "버튼 동작에 필요한 권한 또는 음성비서 앱을 확인하세요"; }
    }
    private void beginGps() {
        if (session == null || !settings.gps()) return;
        if (SystemProperties.getInt("ro.goodman.car.location_bridge", 0) != 1) {
            gpsState = "위치: 폰 GPS · 차량 GPS 공급 기능 준비 안 됨"; return;
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            gpsState = "위치: 폰 GPS · 위치 권한 필요"; return;
        }
        Bundle data = new Bundle(); data.putString("operation", "begin"); data.putString("session", gpsSession);
        try { location.sendExtraCommand(LocationManager.GPS_PROVIDER, "goodman.vehicle", data); gpsStarted = true; }
        catch (SecurityException unavailable) { gpsState = "위치: 폰 GPS · 위치 권한 필요"; }
    }
    private void endGps() {
        if (gpsStarted) {
            Bundle data = new Bundle(); data.putString("operation", "end"); data.putString("session", gpsSession);
            try { location.sendExtraCommand(LocationManager.GPS_PROVIDER, "goodman.vehicle", data); }
            catch (SecurityException ignored) {}
        }
        gpsStarted = false; gps.reset(); gpsState = "위치: 폰 GPS";
    }
    /** The decoder must map the fix's actual capture time to the phone monotonic clock. */
    void onVehicleLocation(Object token, Location fix) {
        requireMain(); if (token != session || session == null || !settings.gps()) return;
        if (!gpsStarted) beginGps();
        if (!gpsStarted || fix == null || fix.isMock() || !fix.isComplete()) return;
        Bundle data = new Bundle(); data.putString("operation", "fix"); data.putString("session", gpsSession);
        data.putParcelable("location", new Location(fix));
        try {
            location.sendExtraCommand(LocationManager.GPS_PROVIDER, "goodman.vehicle", data);
            boolean ready = gps.offer(fix.getElapsedRealtimeNanos() / 1000000,
                    SystemClock.elapsedRealtime(), fix.getAccuracy());
            gpsState = ready ? "차량 GPS 수신 중 · 위치 공급 요청" : "위치: 폰 GPS · 차량 수신 품질 확인 중";
        } catch (SecurityException denied) { endGps(); }
    }
    boolean onMicrophone(Object token, byte[] pcm) { return audio.microphone(token, pcm); }
    private final Runnable callAudioWatch = new Runnable() {
        @Override public void run() {
            if (session == null) return;
            try { audio.setHfpActive(session, hfp.isCallAudioActive()); }
            catch (SecurityException denied) { audio.setHfpActive(session, false); }
            main.postDelayed(this, 500);
        }
    };
    String snapshot() {
        requireMain();
        if (!gps.useVehicle(SystemClock.elapsedRealtime()) && gpsStarted) gpsState = "위치: 폰 GPS · 차량 수신 품질 확인 중";
        StringBuilder result = new StringBuilder(lastAction).append('\n').append(audio.snapshot()).append('\n').append(gpsState);
        if (settings.monitor()) {
            result.append("\n\n최근 버튼 (최대 40개)");
            if (events.isEmpty()) result.append("\n수신한 버튼 없음");
            for (String event : events) result.append('\n').append(event);
        }
        return result.toString();
    }
    @Override public void close() {
        requireMain(); settings.preferences.unregisterOnSharedPreferenceChangeListener(changed);
        disconnect(session); audio.close();
    }
}
