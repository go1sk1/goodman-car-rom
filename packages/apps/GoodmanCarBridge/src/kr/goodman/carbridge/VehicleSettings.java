package kr.goodman.carbridge;

import android.content.Context;
import android.content.SharedPreferences;
import kr.goodman.carbridge.core.VehiclePolicy;

final class VehicleSettings {
    final SharedPreferences preferences;
    VehicleSettings(Context context) { preferences = context.getSharedPreferences("vehicle", 0); }
    VehiclePolicy.Sound sound(VehiclePolicy.Category category) {
        try { return VehiclePolicy.Sound.valueOf(preferences.getString("sound." + category.name(), category.defaultSound.name())); }
        catch (IllegalArgumentException ignored) { return category.defaultSound; }
    }
    VehiclePolicy.ButtonAction action(boolean voice) {
        VehiclePolicy.ButtonAction fallback = voice ? VehiclePolicy.ButtonAction.ASSISTANT : VehiclePolicy.ButtonAction.CALL_TOGGLE;
        try { return VehiclePolicy.ButtonAction.valueOf(preferences.getString(voice ? "voice_action" : "call_action", fallback.name())); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }
    boolean microphone() { return preferences.getBoolean("vehicle_microphone", true); }
    boolean gps() { return preferences.getBoolean("vehicle_gps", true); }
    boolean monitor() { return preferences.getBoolean("button_monitor", false); }
}
