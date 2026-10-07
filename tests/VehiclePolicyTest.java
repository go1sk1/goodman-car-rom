import kr.goodman.carbridge.core.VehiclePolicy;
import kr.goodman.carbridge.core.VehiclePolicy.ButtonAction;

public final class VehiclePolicyTest {
    private static int checks;
    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        VehiclePolicy.GpsSelector gps = new VehiclePolicy.GpsSelector();
        expect(!gps.offer(1000, 1000, 10), "One fix must not switch providers");
        expect(!gps.offer(1000, 1100, 10), "Duplicate must not advance acquisition");
        expect(!gps.offer(2000, 2000, 15), "Two fixes still use phone");
        expect(gps.offer(3000, 3000, 20), "Three strong fixes select vehicle");
        expect(gps.offer(4000, 4000, 45), "Hysteresis keeps a usable vehicle fix");
        expect(!gps.offer(4500, 4500, Float.NaN), "Malformed packets do not refresh vehicle position");
        expect(gps.staleDeadlineMillis() == 9001, "Malformed packets cannot postpone fallback watchdog");
        expect(!gps.offer(5000, 5000, 61), "Weak fix falls back immediately");
        expect(!gps.useVehicle(5000), "Weak fix must not keep vehicle active");
        expect(!gps.offer(6000, 6000, 10), "Reacquisition requires stability");
        expect(!gps.offer(7000, 7000, 10), "Two reacquisition fixes insufficient");
        expect(gps.offer(8000, 8000, 10), "Reacquisition succeeds");
        expect(gps.useVehicle(13000), "Five second boundary remains usable");
        expect(!gps.useVehicle(13001), "Stale vehicle fixes fall back without new packets");
        expect(!gps.offer(13002, 13001, 10), "Future time rejected");
        expect(!gps.offer(13000, 13001, Float.NaN), "Invalid accuracy rejected");
        expect(!gps.offer(7000, 13001, 10), "Old packets rejected");
        gps.offer(14000, 14000, 10);
        gps.offer(15000, 15000, 10);
        expect(!gps.offer(19000, 19000, 10), "Long gap resets acquisition count");
        gps.reset();
        expect(!gps.useVehicle(19000), "Disconnect restores phone");
        VehiclePolicy.ButtonPress button = new VehiclePolicy.ButtonPress();
        expect(button.up(10, false) == ButtonAction.NONE, "Unpaired release ignored");
        button.down(1000, false, ButtonAction.CALL_TOGGLE);
        button.down(1100, true, ButtonAction.CALL_TOGGLE);
        expect(button.up(1200, false) == ButtonAction.CALL_TOGGLE, "Repeated key downs produce one action");
        expect(button.up(1300, false) == ButtonAction.NONE, "Duplicate release ignored");
        button.down(1300, false, ButtonAction.CALL_TOGGLE);
        expect(button.up(1350, false) == ButtonAction.NONE, "Bounce filtered");
        button.down(2000, false, ButtonAction.ASSISTANT);
        expect(button.up(2500, true) == ButtonAction.NONE, "Cancelled gesture ignored");
        button.down(3000, false, ButtonAction.ASSISTANT);
        button.cancel();
        expect(button.up(4000, false) == ButtonAction.NONE, "Disconnect cannot trigger an action");
        button.down(5000, false, ButtonAction.ASSISTANT);
        expect(button.up(16000, false) == ButtonAction.NONE, "Stuck button expires");
        expect(VehiclePolicy.Category.NAVIGATION.defaultSound == VehiclePolicy.Sound.GUIDANCE,
                "Navigation defaults to guidance");
        System.out.println("PASS " + checks + " vehicle policy checks");
    }
}
