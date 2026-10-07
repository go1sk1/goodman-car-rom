package kr.goodman.carbridge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import kr.goodman.carbridge.core.VehiclePolicy;

/** Persistent settings plus a bounded, live button monitor. No synthetic car events. */
public final class CarSettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VehicleSettings settings;
    private VehicleRuntime vehicle;
    private LinearLayout page;
    private TextView state;
    private boolean bound;
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        settings = new VehicleSettings(this);
        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        page.setPadding(padding, padding, padding, padding);
        scroll.addView(page); setContentView(scroll);
        heading("차량 연결 설정");
        state = new TextView(this); page.addView(state);
        heading("차량 버튼");
        toggle("누른 버튼 확인", "button_monitor", false);
        choose("통화 버튼", "call_action", new String[]{"CALL_TOGGLE", "ANSWER", "END_CALL", "NONE"},
                new String[]{"수신 시 받기 · 통화 중 종료 · 대기 시 전화 화면", "전화 받기", "통화 종료", "사용 안 함"}, settings.action(false).name());
        choose("음성인식 버튼", "voice_action", new String[]{"ASSISTANT", "NONE"},
                new String[]{"미러링폰 음성비서", "사용 안 함"}, settings.action(true).name());
        text("차량에서 전달한 버튼과 이 화면에서 받은 버튼을 표시합니다. 차량 자체 기능이 사용하는 버튼은 전달되지 않을 수 있습니다.");
        heading("오디오 세부 설정");
        String[] labels = {"음악과 영상", "게임", "내비 안내", "음성비서", "통화"};
        VehiclePolicy.Category[] categories = VehiclePolicy.Category.values();
        for (int i = 0; i < categories.length; i++) {
            choose(labels[i], "sound." + categories[i].name(),
                    new String[]{"MEDIA", "GUIDANCE", "SYSTEM", "CALL"},
                    new String[]{"미디어", "안내와 음성", "시스템", "통화"}, settings.sound(categories[i]).name());
        }
        text("차량이 제공하는 출력 종류로 연결합니다. 지원하지 않는 출력은 연결 상태에 표시합니다.");
        heading("차량 마이크");
        toggle("Android 녹음에 차량 마이크 사용", "vehicle_microphone", true);
        text("차량 연결과 녹음 앱의 마이크 권한이 필요합니다. 마이크 사용 중지 설정을 따릅니다.");
        heading("내비게이션 위치");
        toggle("차량 GPS 우선 · 수신 약화 시 폰 GPS", "vehicle_gps", true);
        text("정확도 30m 이내 위치가 3회 연속 들어오면 차량 GPS를 사용합니다. 정확도 60m 초과 또는 5초 이상 미수신 시 폰 GPS로 돌아갑니다.");
        mirrorSettings();
        heading("ROM 업데이트");
        button("업데이트 확인 · 이미지 다운로드", () -> startActivity(new Intent(this, RomUpdateActivity.class)));
    }
    private void mirrorSettings() {
        heading("미러링 화면");
        TextView railValue = text("");
        SeekBar rail = new SeekBar(this); rail.setMax(72);
        rail.setProgress(Math.max(0, Math.min(72, settings.preferences.getInt("mirror.rail_dp", 32))));
        railValue.setText("상단 상태 레일 높이: " + rail.getProgress() + "dp");
        rail.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                railValue.setText("상단 상태 레일 높이: " + value + "dp · 0은 숨김");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { settings.preferences.edit().putInt("mirror.rail_dp", bar.getProgress()).apply(); }
        });
        page.addView(rail);
        text("미러링 중 표시하는 추가 상태 레일입니다. 폰의 기본 상태바 크기는 변경하지 않습니다.");
        heading("앱페어 · 분할 화면");
        toggle("내비게이션바 주변에 앱페어 버튼 표시", "mirror.pair_button", true);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        apps.sort(new ResolveInfo.DisplayNameComparator(getPackageManager()));
        ArrayList<String> names = new ArrayList<>(), components = new ArrayList<>();
        names.add("선택 안 함"); components.add("");
        LinkedHashMap<String, String> packages = new LinkedHashMap<>();
        for (ResolveInfo app : apps) {
            String label = app.loadLabel(getPackageManager()).toString();
            String pkg = app.activityInfo.packageName;
            names.add(label + " (" + pkg + ")");
            components.add(new ComponentName(pkg, app.activityInfo.name).flattenToString());
            packages.put(pkg, label);
        }
        choose("첫 번째 앱 · 가로에서는 왼쪽", "mirror.pair_first", components.toArray(new String[0]),
                names.toArray(new String[0]), settings.preferences.getString("mirror.pair_first", ""));
        choose("두 번째 앱 · 가로에서는 오른쪽", "mirror.pair_second", components.toArray(new String[0]),
                names.toArray(new String[0]), settings.preferences.getString("mirror.pair_second", ""));
        choose("첫 번째 앱 : 두 번째 앱", "mirror.pair_ratio", new String[]{"50", "70", "30"},
                new String[]{"5:5", "7:3", "3:7"}, settings.preferences.getString("mirror.pair_ratio", "50"));
        text("분할 화면 미지원 앱은 실행하지 않습니다. 화면 크기·앱 최소 크기에 따라 Android가 비율을 조정할 수 있습니다.");
        heading("미디어 재생 알림 · 앱별 선택");
        text("선택한 앱이 미디어 세션으로 재생을 알릴 때 미러링 화면에 5초간 표시합니다. 음악·영상 구분 없이 앱별로 설정합니다.");
        for (java.util.Map.Entry<String, String> app : packages.entrySet())
            toggle(app.getValue() + " (" + app.getKey() + ")", "mirror.notify." + app.getKey(), false);
        heading("미러링 시작 시 음악 자동재생");
        toggle("선택한 음악 앱 자동재생", "mirror.autoplay", false);
        ArrayList<String> musicIds = new ArrayList<>(), musicNames = new ArrayList<>();
        musicIds.add(""); musicNames.add("선택 안 함");
        for (java.util.Map.Entry<String, String> app : packages.entrySet()) {
            musicIds.add(app.getKey()); musicNames.add(app.getValue() + " (" + app.getKey() + ")");
        }
        choose("자동재생 음악 앱", "mirror.music_app", musicIds.toArray(new String[0]),
                musicNames.toArray(new String[0]), settings.preferences.getString("mirror.music_app", ""));
        text("차량 미디어 출력이 준비되면 연결당 한 번 재생을 요청합니다. 앱의 로그인·재생목록 준비가 필요하며 통화 중에는 실행하지 않습니다.");
        heading("음성 검색 · 음성 대화");
        choose("차량 음성 버튼으로 실행할 앱", "assistant_app", new String[]{"SYSTEM", "GEMINI", "CHATGPT"},
                new String[]{"기본 음성비서", "Gemini / Google", "ChatGPT"}, settings.preferences.getString("assistant_app", "SYSTEM"));
        button("선택한 음성 앱 실행", () -> AssistantLauncher.launch(this));
        button("Google 앱 설치 / 업데이트", () -> AssistantLauncher.install(this, "com.google.android.googlequicksearchbox"));
        button("Gemini 설치 / 업데이트", () -> AssistantLauncher.install(this, "com.google.android.apps.bard"));
        button("ChatGPT 설치 / 업데이트", () -> AssistantLauncher.install(this, "com.openai.chatgpt"));
        button("기본 디지털 어시스턴트 설정", () -> startActivity(new Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)));
        text("앱은 Google Play에서 설치하고 로그인·마이크 권한을 설정하세요. Gemini는 Google 앱의 기본 어시스턴트 설정이 필요합니다. 음성 진입을 제공하지 않는 앱은 앱 화면을 연 뒤 음성 버튼을 눌러야 합니다.");
    }
    private void button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> {
            try { action.run(); } catch (RuntimeException error) { android.widget.Toast.makeText(this, "앱 또는 설정을 열 수 없습니다", android.widget.Toast.LENGTH_LONG).show(); }
        }); page.addView(button);
    }
    private void heading(String value) { TextView v = text(value); v.setTextSize(21); v.setPadding(0, 28, 0, 12); }
    private TextView text(String value) { TextView v = new TextView(this); v.setText(value); page.addView(v); return v; }
    private void toggle(String label, String key, boolean fallback) {
        Switch control = new Switch(this); control.setText(label);
        control.setChecked(settings.preferences.getBoolean(key, fallback));
        control.setOnCheckedChangeListener((v, checked) -> settings.preferences.edit().putBoolean(key, checked).apply());
        page.addView(control);
    }
    private void choose(String label, String key, String[] values, String[] labels, String selected) {
        text(label);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        for (int i = 0; i < values.length; i++) if (values[i].equals(selected)) spinner.setSelection(i);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!values[position].equals(settings.preferences.getString(key, selected)))
                    settings.preferences.edit().putString(key, values[position]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        page.addView(spinner);
    }
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { vehicle = ((CarBridgeService.LocalBinder) binder).vehicle(); }
        @Override public void onServiceDisconnected(ComponentName name) { vehicle = null; }
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            state.setText(vehicle == null ? "Goodman Car에서 연결 서비스를 시작하세요" : vehicle.snapshot());
            handler.postDelayed(this, 1000);
        }
    };
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (vehicle != null && vehicle.onAndroidKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onStart() {
        super.onStart(); bound = bindService(new Intent(this, CarBridgeService.class), connection, 0); handler.post(refresh);
    }
    @Override protected void onStop() {
        handler.removeCallbacks(refresh); if (bound) unbindService(connection); bound = false; vehicle = null; super.onStop();
    }
}
