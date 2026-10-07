package kr.goodman.carbridge;

import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** UI resources belong to one projection session, never to the boot/service lifetime. */
final class MirrorExperience implements AutoCloseable {
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager windows;
    private final MediaSessionManager sessions;
    private final Map<MediaController, MediaController.Callback> watched = new HashMap<>();
    private final Map<String, String> lastPlaying = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionListener = this::watch;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesListener = (p, k) -> {
        if (k != null && k.startsWith("mirror.")) { hide(); render(); }
    };
    private View rail, pair, notice;
    private TextView clock;
    private boolean visible, audioReady, closed, listening, started, autoDone, musicLaunched, overlayFailed;
    private boolean autoplay;
    private String musicPackage = "";
    private long deadline;
    private String geometry = "";
    private boolean expanded;
    private final Runnable dismissNotice = () -> { remove(notice); notice = null; };

    MirrorExperience(Context context) {
        this.context = context;
        prefs = new VehicleSettings(context).preferences;
        windows = context.getSystemService(WindowManager.class);
        sessions = context.getSystemService(MediaSessionManager.class);
        prefs.registerOnSharedPreferenceChangeListener(preferencesListener);
        main.post(tick);
    }

    void setVisible(boolean next) {
        if (closed) return;
        visible = next;
        if (next && !started) {
            started = true;
            autoplay = prefs.getBoolean("mirror.autoplay", false);
            musicPackage = prefs.getString("mirror.music_app", "");
            try {
                sessions.addOnActiveSessionsChangedListener(sessionListener, null, main);
                listening = true;
                watch(sessions.getActiveSessions(null));
            } catch (SecurityException error) { message("미디어 세션 접근 권한을 확인하세요"); }
        }
        if (!next) hide(); else render();
        maybePlay();
    }

    void setAudioReady(boolean ready) { audioReady = ready; maybePlay(); }

    private boolean allowed() {
        return visible && !closed && context.getSystemService(PowerManager.class).isInteractive()
                && !context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                && context.getSystemService(UserManager.class).isUserForeground();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            if (!allowed()) hide(); else {
                String next = windows.getCurrentWindowMetrics().getBounds().toString()
                        + windows.getCurrentWindowMetrics().getWindowInsets()
                            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
                if (!next.equals(geometry)) { geometry = next; hide(); }
                render();
                if (clock != null) clock.setText(android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis()));
                maybePlay();
            }
            main.postDelayed(this, 500);
        }
    };

    private void watch(List<MediaController> controllers) {
        for (Map.Entry<MediaController, MediaController.Callback> entry : watched.entrySet())
            entry.getKey().unregisterCallback(entry.getValue());
        watched.clear();
        if (closed || controllers == null) return;
        for (MediaController controller : controllers) {
            MediaController.Callback callback = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata metadata) { mediaChanged(controller); }
                @Override public void onPlaybackStateChanged(PlaybackState state) { mediaChanged(controller); }
                @Override public void onSessionDestroyed() { lastPlaying.remove(controller.getPackageName()); }
            };
            watched.put(controller, callback); controller.registerCallback(callback, main);
            mediaChanged(controller);
        }
        maybePlay();
    }

    private void mediaChanged(MediaController controller) {
        PlaybackState playback = controller.getPlaybackState();
        String pkg = controller.getPackageName();
        if (playback == null || playback.getState() != PlaybackState.STATE_PLAYING) {
            lastPlaying.remove(pkg); return;
        }
        MediaMetadata metadata = controller.getMetadata();
        String title = metadata == null ? "재생 중" : String.valueOf(metadata.getDescription().getTitle());
        String previous = lastPlaying.put(pkg, title);
        if (!allowed() || !prefs.getBoolean("mirror.notify." + pkg, false) || title.equals(previous)) return;
        remove(notice);
        TextView label = text(appLabel(pkg) + " · " + title, 16);
        label.setMaxLines(2); label.setEllipsize(TextUtils.TruncateAt.END);
        label.setOnClickListener(v -> dismissNotice.run());
        WindowManager.LayoutParams params = params(dp(320), WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        params.y = dp(railHeight()) + insets().top + dp(8);
        params.x = insets().right + dp(8);
        if (add(label, params)) notice = label;
        main.removeCallbacks(dismissNotice); main.postDelayed(dismissNotice, 5000);
    }

    private void maybePlay() {
        if (autoDone || !started || !autoplay || musicPackage.isEmpty()) return;
        if (!allowed() || !audioReady) return;
        if (deadline == 0) deadline = SystemClock.elapsedRealtime() + 15000;
        if (SystemClock.elapsedRealtime() > deadline) { autoDone = true; return; }
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio.getMode() != AudioManager.MODE_NORMAL || audio.isBluetoothScoOn()) {
            autoDone = true; return; // Do not start music over a phone/SCO call.
        }
        for (MediaController controller : watched.keySet()) {
            if (!musicPackage.equals(controller.getPackageName())) continue;
            PlaybackState state = controller.getPlaybackState();
            if (state == null) continue;
            if (state.getState() == PlaybackState.STATE_PLAYING) { autoDone = true; return; }
            if ((state.getActions() & PlaybackState.ACTION_PLAY) == 0) continue;
            autoDone = true;
            try { controller.getTransportControls().play(); }
            catch (RuntimeException error) { message("선택한 음악 앱의 재생 요청 실패"); }
            return;
        }
        if (!musicLaunched) {
            musicLaunched = true;
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(musicPackage);
            if (launch != null) {
                try { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (RuntimeException error) { autoDone = true; message("음악 앱을 열 수 없습니다"); }
            } else { autoDone = true; message("자동재생 음악 앱을 다시 선택하세요"); }
        }
        // Wait for the selected app's media session, without replaying keys or selecting another app.
    }

    private int railHeight() {
        return Math.max(0, Math.min(72, prefs.getInt("mirror.rail_dp", 32)));
    }
    private Insets insets() {
        return windows.getCurrentWindowMetrics().getWindowInsets()
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
    }
    private void render() {
        if (!allowed() || overlayFailed) return;
        if (rail == null && railHeight() > 0) {
            clock = text("", Math.min(20, Math.max(12, railHeight() / 2)));
            clock.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            WindowManager.LayoutParams p = params(WindowManager.LayoutParams.MATCH_PARENT,
                    dp(railHeight()), Gravity.TOP);
            p.y = insets().top;
            if (add(clock, p)) rail = clock; else clock = null;
        }
        if (pair == null && prefs.getBoolean("mirror.pair_button", true)) {
            LinearLayout row = new LinearLayout(context);
            row.setBackgroundColor(0xee172535);
            Button button = new Button(context); button.setText("앱페어");
            button.setContentDescription("앱페어 비율 선택");
            button.setOnClickListener(v -> {
                String selected = prefs.getString("mirror.pair_ratio", "50");
                launchPair("70".equals(selected) ? 70 : "30".equals(selected) ? 30 : 50);
            });
            row.addView(button);
            Button ratios = new Button(context); ratios.setText("비율");
            ratios.setOnClickListener(v -> { expanded = !expanded; remove(pair); pair = null; render(); });
            row.addView(ratios);
            if (expanded) for (int ratio : new int[]{50, 70, 30}) {
                Button choice = new Button(context); choice.setText((ratio / 10) + ":" + ((100 - ratio) / 10));
                choice.setOnClickListener(v -> launchPair(ratio)); row.addView(choice);
            }
            button.setOnLongClickListener(v -> {
                context.startActivity(new Intent(context, CarSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            });
            WindowManager.LayoutParams p = params(WindowManager.LayoutParams.WRAP_CONTENT, dp(48), Gravity.BOTTOM | Gravity.END);
            p.x = insets().right; p.y = insets().bottom;
            if (add(row, p)) pair = row;
        }
    }
    private void launchPair(int ratio) {
        if (!allowed()) return;
        String first = prefs.getString("mirror.pair_first", "");
        String second = prefs.getString("mirror.pair_second", "");
        if (first.isEmpty() || second.isEmpty() || first.equals(second)) {
            message("설정에서 서로 다른 앱 두 개를 선택하세요 · 앱페어 버튼 길게 누르기"); return;
        }
        prefs.edit().putString("mirror.pair_ratio", Integer.toString(ratio)).apply();
        Intent request = new Intent("kr.goodman.carbridge.action.START_APP_PAIR").setPackage("com.android.systemui")
                .putExtra("first", first).putExtra("second", second).putExtra("ratio", ratio);
        BroadcastOptions options = BroadcastOptions.makeBasic(); options.setShareIdentityEnabled(true);
        context.sendBroadcast(request, null, options.toBundle());
        expanded = false; remove(pair); pair = null; render();
    }
    private WindowManager.LayoutParams params(int width, int height, int gravity) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = gravity; p.setTitle("Goodman mirroring controls"); p.setFitInsetsTypes(0);
        return p;
    }
    private TextView text(String value, int size) {
        TextView v = new TextView(context); v.setText(value); v.setTextSize(size); v.setTextColor(Color.WHITE);
        v.setPadding(dp(8), 0, dp(8), 0); v.setBackgroundColor(0xee172535); return v;
    }
    private boolean add(View view, WindowManager.LayoutParams params) {
        try { windows.addView(view, params); return true; }
        catch (RuntimeException error) {
            if (!overlayFailed) message("미러링 UI를 표시할 수 없습니다 · 시스템 권한 확인 필요");
            overlayFailed = true; return false;
        }
    }
    private void remove(View view) {
        if (view != null && view.isAttachedToWindow()) windows.removeViewImmediate(view);
    }
    private void hide() {
        remove(rail); remove(pair); remove(notice); rail = pair = notice = null; clock = null;
        main.removeCallbacks(dismissNotice);
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void message(String message) { Toast.makeText(context, message, Toast.LENGTH_LONG).show(); }
    private String appLabel(String pkg) {
        try { return context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { return pkg; }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; main.removeCallbacks(tick); hide();
        prefs.unregisterOnSharedPreferenceChangeListener(preferencesListener);
        if (listening) sessions.removeOnActiveSessionsChangedListener(sessionListener);
        for (Map.Entry<MediaController, MediaController.Callback> entry : watched.entrySet())
            entry.getKey().unregisterCallback(entry.getValue());
        watched.clear(); lastPlaying.clear();
    }
}
