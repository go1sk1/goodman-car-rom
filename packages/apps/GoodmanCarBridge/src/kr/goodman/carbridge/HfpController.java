package kr.goodman.carbridge;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothHeadsetClientCall;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ROM-only HFP HF controller. Commands enqueue requests; stack state is authoritative. */
final class HfpController implements AutoCloseable {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private BluetoothHeadsetClient profile;
    private BluetoothDevice selected;
    private boolean started;
    private boolean reconnectRequested;
    private boolean wasConnected;
    private int reconnectAttempt;
    private static final long[] RECONNECT_DELAYS_MS = {2_000, 5_000, 10_000, 20_000, 30_000};
    private String result = "HFP 초기화 전";

    HfpController(Context context) {
        this.context = context;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    void start() {
        if (started) return;
        if (adapter == null) { result = "Bluetooth 어댑터 없음"; return; }
        started = true;
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadsetClient.ACTION_CALL_CHANGED);
        filter.addAction(BluetoothHeadsetClient.ACTION_AUDIO_STATE_CHANGED);
        // Broadcast payloads are never used to issue call commands.
        context.registerReceiver(events, filter, Manifest.permission.BLUETOOTH_CONNECT,
                handler, Context.RECEIVER_EXPORTED);
        String address = context.getSharedPreferences("car", 0).getString("main_phone", "");
        if (BluetoothAdapter.checkBluetoothAddress(address)) {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) selected = device;
        }
        reconnectRequested = context.getSharedPreferences("car", 0)
                .getBoolean("reconnect_main_phone", false);
        boolean accepted = adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET_CLIENT);
        result = accepted ? "HFP 시스템 서비스 연결 중" : "ROM의 HFP Client 활성화 확인 필요";
    }

    List<BluetoothDevice> bondedDevices() {
        return adapter == null ? Collections.emptyList() : new ArrayList<>(adapter.getBondedDevices());
    }

    void connect(BluetoothDevice device) {
        if (!started || profile == null) { result = "HFP 서비스 준비 안 됨"; return; }
        if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED) {
            result = "설정에서 메인폰과 먼저 페어링하세요"; return;
        }
        if (!calls().isEmpty()) { result = "통화 종료 후 상대 기기를 변경하세요"; return; }
        // Avoid a selection silently disconnecting a different active phone.
        for (BluetoothDevice connected : profile.getConnectedDevices()) {
            if (!connected.equals(device)) { result = "다른 HFP 기기를 먼저 연결 해제하세요"; return; }
        }
        selected = device;
        reconnectRequested = true;
        reconnectAttempt = 0;
        handler.removeCallbacks(reconnect);
        context.getSharedPreferences("car", 0).edit().putString("main_phone", device.getAddress())
                .putBoolean("reconnect_main_phone", true).apply();
        if (profile.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
            result = "HFP 연결됨"; return;
        }
        if (!profile.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED)) {
            result = "HFP 연결 정책 설정 실패";
            return;
        }
        // HeadsetClientService starts connecting when the policy becomes ALLOWED.
        result = "HFP 연결 정책 적용 · 연결 상태 확인 중";
        scheduleReconnect();
    }

    void disconnect() {
        if (!calls().isEmpty()) { result = "통화를 종료한 뒤 연결을 해제하세요"; return; }
        reconnectRequested = false;
        handler.removeCallbacks(reconnect);
        context.getSharedPreferences("car", 0).edit().putBoolean("reconnect_main_phone", false).apply();
        if (profile == null || selected == null) { result = "자동 재연결 꺼짐"; return; }
        // The service also disconnects when applying FORBIDDEN; avoid duplicate commands.
        command(profile.setConnectionPolicy(selected, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN),
                "HFP 연결 해제");
    }

    void dial(String number) {
        if (!ready()) return;
        if (!number.matches("\\+?[0-9]{3,20}")) { result = "전화번호 형식 확인 필요"; return; }
        if (!calls().isEmpty()) { result = "진행 중인 통화를 먼저 종료하세요"; return; }
        command(profile.dial(selected, number) != null, "발신");
    }

    void answer() {
        if (!ready()) return;
        boolean ringing = false;
        boolean active = false;
        for (BluetoothHeadsetClientCall call : calls()) {
            ringing |= call.getState() == BluetoothHeadsetClientCall.CALL_STATE_INCOMING
                    || call.getState() == BluetoothHeadsetClientCall.CALL_STATE_WAITING;
            active |= call.getState() == BluetoothHeadsetClientCall.CALL_STATE_ACTIVE;
        }
        if (!ringing) { result = "수신 중인 전화 없음"; return; }
        command(profile.acceptCall(selected, active ? BluetoothHeadsetClient.CALL_ACCEPT_HOLD
                : BluetoothHeadsetClient.CALL_ACCEPT_NONE), "전화 받기");
    }

    void reject() {
        if (!ready()) return;
        for (BluetoothHeadsetClientCall call : calls()) {
            if (call.getState() == BluetoothHeadsetClientCall.CALL_STATE_INCOMING
                    || call.getState() == BluetoothHeadsetClientCall.CALL_STATE_WAITING) {
                command(profile.rejectCall(selected), "수신 거절");
                return;
            }
        }
        result = "수신 중인 전화 없음";
    }

    void hangUpAll() {
        if (ready()) command(profile.terminateCall(selected, null), "활성 통화 모두 종료");
    }

    boolean handleCallButton() {
        List<BluetoothHeadsetClientCall> current = calls();
        if (current.isEmpty()) return false;
        for (BluetoothHeadsetClientCall call : current) {
            if (call.getState() == BluetoothHeadsetClientCall.CALL_STATE_INCOMING
                    || call.getState() == BluetoothHeadsetClientCall.CALL_STATE_WAITING) {
                answer(); return true;
            }
        }
        hangUpAll(); return true;
    }

    boolean isCallAudioActive() {
        if (profile == null || selected == null
                || profile.getAudioState(selected) != BluetoothHeadsetClient.STATE_AUDIO_CONNECTED) return false;
        for (BluetoothHeadsetClientCall call : calls())
            if (call.getState() == BluetoothHeadsetClientCall.CALL_STATE_ACTIVE) return true;
        return false;
    }

    void connectAudio() {
        if (!ready()) return;
        profile.setAudioRouteAllowed(selected, true);
        command(profile.connectAudio(selected), "SCO 음성 연결");
    }

    void disconnectAudio() {
        if (!ready()) return;
        profile.setAudioRouteAllowed(selected, false);
        command(profile.disconnectAudio(selected), "음성을 메인폰으로 전환");
    }

    void sendDtmf(char digit) {
        if (!ready()) return;
        if ("0123456789*#".indexOf(digit) < 0) { result = "지원하지 않는 통화 키"; return; }
        for (BluetoothHeadsetClientCall call : calls()) {
            if (call.getState() == BluetoothHeadsetClientCall.CALL_STATE_ACTIVE) {
                command(profile.sendDTMF(selected, (byte) digit), "통화 키 전송");
                return;
            }
        }
        result = "연결된 통화에서만 키를 전송할 수 있습니다";
    }

    String snapshot() {
        if (profile == null) return result;
        String phone = selected == null ? "선택 안 됨" : selected.getName();
        int connection = selected == null ? BluetoothProfile.STATE_DISCONNECTED : profile.getConnectionState(selected);
        int audio = selected == null ? BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED : profile.getAudioState(selected);
        StringBuilder text = new StringBuilder("메인폰: ").append(phone)
                .append("\nHFP 연결 상태: ").append(connection)
                .append(" · SCO 상태: ").append(audio)
                .append("\n").append(result);
        for (BluetoothHeadsetClientCall call : calls()) {
            text.append("\n통화 #").append(call.getId()).append(" 상태: ").append(call.getState());
        }
        return text.toString();
    }

    private boolean ready() {
        if (profile != null && selected != null
                && profile.getConnectionState(selected) == BluetoothProfile.STATE_CONNECTED) return true;
        result = "메인폰 HFP 연결 필요";
        return false;
    }

    private List<BluetoothHeadsetClientCall> calls() {
        if (profile == null || selected == null) return Collections.emptyList();
        List<BluetoothHeadsetClientCall> calls = profile.getCurrentCalls(selected);
        return calls == null ? Collections.emptyList() : calls;
    }

    private void command(boolean queued, String label) {
        result = label + (queued ? " 요청 전달 · 결과는 통화 상태 확인" : " 요청 실패");
    }

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int type, BluetoothProfile proxy) {
            if (type != BluetoothProfile.HEADSET_CLIENT) return;
            if (!started) { adapter.closeProfileProxy(type, proxy); return; }
            profile = (BluetoothHeadsetClient) proxy;
            result = "HFP 시스템 서비스 준비됨";
            reconnectAttempt = 0;
            scheduleReconnect();
        }
        @Override public void onServiceDisconnected(int type) {
            if (type == BluetoothProfile.HEADSET_CLIENT) {
                profile = null;
                handler.removeCallbacks(reconnect);
                result = "HFP 시스템 서비스 연결 끊김";
            }
        }
    };

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            // Query the stack; broadcast extras never select a device or initiate a call.
            try {
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())
                        && adapter.isEnabled()) reconnectAttempt = 0;
                if (profile != null && selected != null) {
                    boolean connected = profile.getConnectionState(selected) == BluetoothProfile.STATE_CONNECTED;
                    if (connected || wasConnected) reconnectAttempt = 0;
                    wasConnected = connected;
                    if (connected) handler.removeCallbacks(reconnect);
                    else scheduleReconnect();
                }
            } catch (SecurityException denied) { result = "Bluetooth 권한 필요"; }
        }
    };

    private void scheduleReconnect() {
        handler.removeCallbacks(reconnect);
        if (started && reconnectRequested && selected != null && profile != null
                && reconnectAttempt < RECONNECT_DELAYS_MS.length) {
            handler.postDelayed(reconnect, RECONNECT_DELAYS_MS[reconnectAttempt]);
        }
    }

    private final Runnable reconnect = new Runnable() {
        @Override public void run() {
            if (!started || !reconnectRequested || profile == null || selected == null) return;
            try {
                if (!adapter.isEnabled() || selected.getBondState() != BluetoothDevice.BOND_BONDED) return;
                int state = profile.getConnectionState(selected);
                if (state == BluetoothProfile.STATE_CONNECTED) { wasConnected = true; return; }
                // Do not switch away from another phone that the Bluetooth stack has connected.
                for (BluetoothDevice device : profile.getConnectedDevices()) {
                    if (!selected.equals(device)) return;
                }
                reconnectAttempt++;
                if (state == BluetoothProfile.STATE_DISCONNECTED
                        && profile.getConnectionPolicy(selected) == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
                    command(profile.connect(selected), "메인폰 자동 재연결");
                }
                scheduleReconnect();
            } catch (SecurityException denied) { result = "Bluetooth 권한 필요"; }
        }
    };

    @Override public void close() {
        if (!started) return;
        started = false;
        handler.removeCallbacks(reconnect);
        context.unregisterReceiver(events);
        if (profile != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET_CLIENT, profile);
        profile = null;
    }
}
