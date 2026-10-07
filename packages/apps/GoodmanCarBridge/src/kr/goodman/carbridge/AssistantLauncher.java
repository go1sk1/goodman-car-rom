package kr.goodman.carbridge;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

/** Uses only exported Android entry points; never assumes an undocumented voice deep link. */
final class AssistantLauncher {
    static void launch(Context context) {
        if (context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            notice(context, "미러링폰의 잠금을 해제하세요"); return;
        }
        String selected = new VehicleSettings(context).preferences.getString("assistant_app", "SYSTEM");
        String pkg = "GEMINI".equals(selected) ? "com.google.android.googlequicksearchbox"
                : "CHATGPT".equals(selected) ? "com.openai.chatgpt" : null;
        for (String action : new String[]{Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST}) {
            Intent intent = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pkg != null) intent.setPackage(pkg);
            ResolveInfo resolved = context.getPackageManager().resolveActivity(intent, 0);
            if (resolved != null && resolved.activityInfo != null && resolved.activityInfo.exported) {
                try { context.startActivity(intent); return; }
                catch (SecurityException | android.content.ActivityNotFoundException ignored) {}
            }
        }
        if (pkg != null) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                notice(context, "앱에서 음성 버튼을 눌러 시작하세요"); return;
            }
        }
        notice(context, "차량 설정에서 음성 앱 설치·로그인·기본 어시스턴트 설정을 완료하세요");
    }
    static void install(Context context, String pkg) {
        if (!pkg.equals("com.openai.chatgpt") && !pkg.equals("com.google.android.apps.bard")
                && !pkg.equals("com.google.android.googlequicksearchbox")) throw new IllegalArgumentException("Unsupported app");
        Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg))
                .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(market); }
        catch (android.content.ActivityNotFoundException missing) {
            context.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
    private static void notice(Context context, String value) { Toast.makeText(context, value, Toast.LENGTH_LONG).show(); }
}
