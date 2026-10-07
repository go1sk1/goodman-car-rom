package kr.goodman.carbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbAccessory;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

public final class CarActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HfpController controller;
    private VehicleRuntime vehicle;
    private UsbProjectionController usb;
    private boolean bound;
    private TextView status;
    private EditText number;
    private LinearLayout page;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(24, 24, 24, 24);
        scroll.addView(page);
        setContentView(scroll);
        status = new TextView(this);
        status.setTextSize(20);
        page.addView(status);
        button("HFP 서비스 시작", this::startBridge);
        button("차량 USB 연결", () -> {
            if (usb == null) { status.setText("먼저 HFP 서비스를 시작하세요"); return; }
            UsbAccessory[] accessories = usb.accessories();
            if (accessories.length == 0) {
                new AlertDialog.Builder(this).setMessage("차량 Android Auto USB 포트에 연결한 뒤 다시 선택하세요")
                        .setPositiveButton("확인", null).show(); return;
            }
            String[] labels = new String[accessories.length];
            for (int i = 0; i < labels.length; i++) labels[i] = UsbProjectionController.label(accessories[i]);
            new AlertDialog.Builder(this).setTitle("연결할 차량 선택").setItems(labels, (dialog, which) -> {
                if (usb != null) usb.connect(accessories[which]);
            }).show();
        });
        button("처음 연결한 차량 등록", () -> {
            if (usb == null) return;
            UsbProjectionController.Enrollment candidate = usb.enrollment();
            if (candidate == null) {
                new AlertDialog.Builder(this).setMessage("차량 USB 연결을 먼저 시작하세요. 등록할 차량이 확인되면 여기에 표시됩니다.")
                        .setPositiveButton("확인", null).show(); return;
            }
            new AlertDialog.Builder(this).setTitle("이 차량에 연결하시겠습니까?")
                    .setMessage(candidate.name + "\n\n차량 인증서\n" + candidate.fingerprint)
                    .setNegativeButton("취소", (dialog, which) -> { if (usb != null) usb.disconnect(); })
                    .setPositiveButton("차량 등록", (dialog, which) -> {
                        if (usb != null && !usb.approve(candidate)) status.setText("차량 연결이 바뀌었습니다. 다시 연결하세요");
                    }).show();
        });
        button("차량 연결 해제", () -> { if (usb != null) usb.disconnect(); });
        button("ccNC 화면으로 나가기", () -> { if (usb != null) usb.showVehicleScreen(); });
        button("미러링 화면으로 돌아오기", () -> { if (usb != null) usb.showMirrorScreen(); });
        button("선택한 차량 등록 삭제", () -> {
            if (usb == null) return;
            new AlertDialog.Builder(this).setTitle("선택한 차량의 등록 정보를 삭제할까요?")
                    .setNegativeButton("취소", null).setPositiveButton("삭제", (dialog, which) -> {
                        if (usb != null) usb.forgetSelected();
                    }).show();
        });
        button("차량 버튼 · 오디오 · 마이크 · GPS 설정", () -> startActivity(new Intent(this, CarSettingsActivity.class)));
        button("메인폰 선택 / 연결", () -> {
            if (controller == null) return;
            List<BluetoothDevice> devices = controller.bondedDevices();
            if (devices.isEmpty()) {
                new AlertDialog.Builder(this).setMessage("Bluetooth 설정에서 메인폰과 먼저 페어링하세요")
                        .setPositiveButton("확인", null).show();
                return;
            }
            String[] names = new String[devices.size()];
            for (int i = 0; i < names.length; i++) {
                String name = devices.get(i).getName();
                names[i] = name == null ? devices.get(i).getAddress() : name;
            }
            new AlertDialog.Builder(this).setTitle("페어링된 메인폰 선택")
                    .setItems(names, (d, i) -> { if (controller != null) controller.connect(devices.get(i)); }).show();
        });
        button("Bluetooth 설정", () -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        number = new EditText(this);
        number.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        number.setHint("발신 번호");
        page.addView(number);
        button("발신", () -> {
            if (controller == null) return;
            String digits = number.getText().toString().trim();
            new AlertDialog.Builder(this).setTitle("메인폰 회선으로 발신")
                    .setMessage(digits).setNegativeButton("취소", null)
                    .setPositiveButton("발신", (d, i) -> { if (controller != null) controller.dial(digits); }).show();
        });
        button("전화 받기", () -> { if (controller != null) controller.answer(); });
        button("수신 거절", () -> { if (controller != null) controller.reject(); });
        button("활성 통화 모두 종료", () -> { if (controller != null) controller.hangUpAll(); });
        button("SCO 음성 연결", () -> { if (controller != null) controller.connectAudio(); });
        button("음성을 메인폰으로 전환", () -> { if (controller != null) controller.disconnectAudio(); });
        button("통화 중 키패드", () -> {
            String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("통화 중 키패드")
                    .setItems(keys, null).setNegativeButton("닫기", null).create();
            dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
                if (controller != null) {
                    try { controller.sendDtmf(keys[position].charAt(0)); }
                    catch (SecurityException denied) { status.setText("Bluetooth 권한 필요"); }
                }
            }));
            dialog.show();
        });
        button("HFP 연결 해제", () -> { if (controller != null) controller.disconnect(); });
        button("서비스 및 자동 시작 끄기", () -> {
            getSharedPreferences("car", 0).edit().putBoolean("enabled", false).apply();
            unbindBridge();
            stopService(new Intent(this, CarBridgeService.class));
        });
    }

    private void startBridge() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            return;
        }
        getSharedPreferences("car", 0).edit().putBoolean("enabled", true).apply();
        Intent service = new Intent(this, CarBridgeService.class);
        startForegroundService(service);
        if (!bound) bound = bindService(service, connection, BIND_AUTO_CREATE);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            controller = ((CarBridgeService.LocalBinder) binder).controller();
            vehicle = ((CarBridgeService.LocalBinder) binder).vehicle();
            usb = ((CarBridgeService.LocalBinder) binder).usb();
        }
        @Override public void onServiceDisconnected(ComponentName name) { controller = null; vehicle = null; usb = null; }
    };

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startBridge();
        }
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            try { status.setText(controller == null ? "ROM HFP 서비스 대기" : controller.snapshot()
                    + (usb == null ? "" : "\n\n" + usb.snapshot())); }
            catch (SecurityException e) { status.setText("Bluetooth 권한 필요"); }
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onStart() {
        super.onStart();
        if (getSharedPreferences("car", 0).getBoolean("enabled", false)) startBridge();
        handler.post(refresh);
    }
    @Override protected void onStop() { handler.removeCallbacks(refresh); unbindBridge(); super.onStop(); }
    private void unbindBridge() { if (bound) unbindService(connection); bound = false; controller = null; vehicle = null; usb = null; }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (vehicle != null && vehicle.onAndroidKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
    private void button(String text, Runnable action) {
        Button button = new Button(this); button.setText(text);
        button.setOnClickListener(v -> {
            try { action.run(); }
            catch (SecurityException e) { status.setText("ROM 권한 설정 또는 Bluetooth 권한을 확인하세요"); }
        });
        page.addView(button);
    }
}
