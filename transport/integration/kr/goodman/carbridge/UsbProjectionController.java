package kr.goodman.carbridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import kr.goodman.carbridge.transport.ProjectionIdentity;
import kr.goodman.carbridge.transport.UsbAccessoryLink;

/** Explicit USB selection and per-vehicle certificate enrollment; never starts from an exported intent. */
final class UsbProjectionController implements AutoCloseable {
    private static final String PERMISSION = "kr.goodman.carbridge.USB_PERMISSION";
    static final class Enrollment {
        final String name, fingerprint;
        private final byte[] pin;
        private final long generation;
        private Enrollment(UsbAccessory accessory, byte[] pin, long generation) {
            name = label(accessory); this.pin = pin.clone(); fingerprint = hex(pin); this.generation = generation;
        }
    }
    private final Context context;
    private final UsbManager usb;
    private final SharedPreferences trust;
    private final ProjectionCoordinator coordinator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor setup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> new Thread(task, "car-usb-setup"));
    private UsbAccessory selected;
    private PendingIntent permission;
    private Enrollment enrollment;
    private volatile long generation;
    private volatile boolean closed;
    private String state = "USB 차량 연결 대기";
    private boolean preparing, attached;

    UsbProjectionController(Context context, ProjectionCoordinator coordinator) {
        requireMain(); this.context = context; this.coordinator = coordinator;
        usb = context.getSystemService(UsbManager.class);
        trust = context.getSharedPreferences("vehicle-certificates", Context.MODE_PRIVATE);
        IntentFilter filter = new IntentFilter(PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    UsbAccessory[] accessories() {
        requireMain(); UsbAccessory[] result = usb.getAccessoryList();
        return result == null ? new UsbAccessory[0] : result.clone();
    }
    static String label(UsbAccessory accessory) {
        return accessory.getManufacturer() + " · " + accessory.getModel();
    }
    Enrollment enrollment() { requireMain(); return enrollment; }
    void showVehicleScreen() { requireMain(); coordinator.showVehicleScreen(); }
    void showMirrorScreen() { requireMain(); coordinator.showMirrorScreen(); }
    String snapshot() {
        requireMain();
        if (enrollment != null) return "처음 연결한 차량입니다 · 차량 등록 버튼을 눌러 확인하세요";
        return preparing || !attached ? state : coordinator.snapshot();
    }

    void connect(UsbAccessory accessory) {
        requireMain();
        if (closed) return;
        disconnect();
        if (accessory == null || !Arrays.asList(accessories()).contains(accessory)) {
            state = "연결된 USB 차량을 찾지 못했습니다"; return;
        }
        selected = accessory;
        if (usb.hasPermission(accessory)) prepare();
        else {
            state = "USB 연결 허용 대기";
            Intent response = new Intent(PERMISSION).setPackage(context.getPackageName())
                    .putExtra("generation", generation);
            permission = PendingIntent.getBroadcast(context, 110, response,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
            usb.requestPermission(accessory, permission);
        }
    }

    boolean approve(Enrollment candidate) {
        requireMain();
        if (closed || candidate == null || candidate != enrollment || candidate.generation != generation
                || selected == null || !usb.hasPermission(selected)
                || !Arrays.asList(accessories()).contains(selected)) return false;
        trust.edit().putString(identity(selected), hex(candidate.pin)).apply();
        UsbAccessory accessory = selected; connect(accessory); return true;
    }
    void forgetSelected() {
        requireMain();
        if (selected != null) trust.edit().remove(identity(selected)).apply();
        disconnect();
    }

    private void prepare() {
        requireMain();
        if (closed || selected == null || preparing) return;
        UsbAccessory accessory = selected; long attempt = generation;
        byte[] enrolled = decodePin(trust.getString(identity(accessory), null));
        preparing = true; state = enrolled == null ? "차량 등록 정보를 확인하는 중" : "USB 차량 연결 준비 중";
        try {
            setup.execute(() -> {
                UsbAccessoryLink link = null;
                try {
                    SSLContext tls = enrolled == null ? ProjectionIdentity.enrollmentProbe(pin -> main.post(() -> {
                        if (!closed && attempt == generation && selected != null && enrollment == null)
                            enrollment = new Enrollment(accessory, pin, attempt);
                    })) : ProjectionIdentity.context(enrolled);
                    if (closed || attempt != generation) return;
                    link = new UsbAccessoryLink(usb, accessory);
                    UsbAccessoryLink owned = link;
                    main.post(() -> {
                        if (closed || attempt != generation) { closeLink(owned); return; }
                        preparing = false;
                        try { coordinator.connect(owned, tls); attached = true; }
                        catch (IOException | RuntimeException failure) {
                            closeLink(owned); attached = false; state = "USB 차량 연결 실패 · 케이블을 다시 연결하세요";
                        }
                    });
                } catch (Exception failure) {
                    closeLink(link);
                    main.post(() -> {
                        if (closed || attempt != generation) return;
                        preparing = false; attached = false;
                        state = "USB 또는 차량 인증서 준비 실패 · 연결 상태를 확인하세요";
                    });
                }
            });
        } catch (RejectedExecutionException busy) { preparing = false; state = "이전 USB 연결을 정리하는 중입니다"; }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (closed || selected == null) return;
            UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory.class);
            if (!selected.equals(accessory)) return;
            if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(intent.getAction())) { disconnect(); return; }
            if (!PERMISSION.equals(intent.getAction()) || intent.getLongExtra("generation", -1) != generation) return;
            if (usb.hasPermission(selected)) prepare(); else state = "USB 연결이 허용되지 않았습니다";
        }
    };
    void disconnect() {
        requireMain(); generation++; preparing = false; attached = false; enrollment = null; selected = null;
        setup.getQueue().clear();
        if (permission != null) { permission.cancel(); permission = null; }
        coordinator.disconnect(); state = "USB 차량 연결 대기";
    }
    @Override public void close() {
        requireMain(); if (closed) return;
        closed = true; disconnect(); context.unregisterReceiver(receiver); setup.shutdownNow();
    }
    private static void closeLink(UsbAccessoryLink link) {
        if (link != null) try { link.close(); } catch (IOException ignored) {}
    }
    private static String identity(UsbAccessory accessory) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            for (String field : new String[]{accessory.getManufacturer(), accessory.getModel(),
                    accessory.getVersion(), accessory.getSerial()}) data.writeUTF(field == null ? "" : field);
            return "usb." + hex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid USB accessory identity", invalid); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
    private static byte[] decodePin(String value) {
        if (value == null || value.length() != 64) return null;
        byte[] result = new byte[32];
        for (int i = 0; i < 32; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16), lo = Character.digit(value.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            result[i] = (byte) ((hi << 4) | lo);
        }
        return result;
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("USB control requires main thread");
    }
}
