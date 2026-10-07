package com.android.wm.shell.splitscreen;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.widget.Toast;
import java.util.concurrent.Executor;

/** Receives requests only from the ROM's privileged companion in the foreground user. */
final class GoodmanPairReceiver extends BroadcastReceiver {
    private static final String ACTION = "kr.goodman.carbridge.action.START_APP_PAIR";
    private static final String PACKAGE = "kr.goodman.carbridge";
    interface Starter { void start(PendingIntent first, PendingIntent second, int user, int ratio); }
    private final Context context;
    private final Executor executor;
    private final Starter starter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastRequest;
    private GoodmanPairReceiver(Context context, Executor executor, Starter starter) {
        this.context = context; this.executor = executor; this.starter = starter;
    }
    static void register(Context context, Executor executor, Starter starter) {
        context.registerReceiverAsUser(new GoodmanPairReceiver(context, executor, starter),
                UserHandle.ALL, new IntentFilter(ACTION), "android.permission.MANAGE_ACTIVITY_TASKS",
                new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
    }
    @Override public void onReceive(Context ignored, Intent request) {
        if (!ACTION.equals(request.getAction())) return;
        int sender = getSentFromUid();
        int user = ActivityManager.getCurrentUser();
        try {
            if (sender < 0 || UserHandle.getUserId(sender) != user
                    || context.getPackageManager().getPackageUidAsUser(PACKAGE, user) != sender) return;
            if (context.getSystemService(KeyguardManager.class).isKeyguardLocked()) { notice("잠금을 해제한 뒤 앱페어를 실행하세요"); return; }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastRequest < 1000) return;
            lastRequest = now;
            int ratio = request.getIntExtra("ratio", 50);
            if (ratio != 30 && ratio != 50 && ratio != 70) return;
            Intent first = launcher(request.getStringExtra("first"), user);
            Intent second = launcher(request.getStringExtra("second"), user);
            if (first == null || second == null || first.getComponent().getPackageName()
                    .equals(second.getComponent().getPackageName())) {
                notice("분할 화면을 지원하는 서로 다른 앱 두 개를 선택하세요"); return;
            }
            Context target = context.createContextAsUser(UserHandle.of(user), 0);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent a = PendingIntent.getActivity(target, 62001, first, flags);
            PendingIntent b = PendingIntent.getActivity(target, 62002, second, flags);
            executor.execute(() -> {
                if (ActivityManager.getCurrentUser() != user
                        || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                try { starter.start(a, b, user, ratio); }
                catch (RuntimeException error) { notice("앱페어 실행 실패 · 앱의 분할 화면 지원 여부를 확인하세요"); }
            });
        } catch (RuntimeException | PackageManager.NameNotFoundException error) {
            notice("앱페어에 선택한 앱을 확인하세요");
        }
    }
    private Intent launcher(String flattened, int user) {
        if (flattened == null) return null;
        ComponentName component = ComponentName.unflattenFromString(flattened);
        if (component == null) return null;
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        ResolveInfo result = context.getPackageManager().resolveActivityAsUser(intent, 0, user);
        if (result == null || result.activityInfo == null) return null;
        ActivityInfo info = result.activityInfo;
        if (!info.exported || !info.enabled || !info.applicationInfo.enabled
                || !ActivityInfo.isResizeableMode(info.resizeMode)) return null;
        return intent;
    }
    private void notice(String text) {
        main.post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }
}
