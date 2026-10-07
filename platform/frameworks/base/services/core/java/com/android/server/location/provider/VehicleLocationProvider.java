package com.android.server.location.provider;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationResult;
import android.location.provider.ProviderRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import com.android.server.FgThread;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import kr.goodman.carbridge.core.VehiclePolicy.GpsSelector;

/** Real vehicle GNSS input with warm phone GNSS fallback. Never uses a mock provider. */
public final class VehicleLocationProvider extends DelegateLocationProvider {
    private static final String PACKAGE = "kr.goodman.carbridge";
    private final Context context;
    private final Handler handler = FgThread.getHandler();
    private final GpsSelector selector = new GpsSelector();
    private volatile String session;
    private volatile boolean active;
    private volatile String selectedSource = "phone";
    private Location phoneFix;
    private long publishedNanos;

    public VehicleLocationProvider(Context context, AbstractLocationProvider delegate) {
        super(FgThread.getExecutor(), delegate);
        this.context = context;
        initializeDelegate();
    }

    @Override protected void onSetRequest(ProviderRequest request) {
        active = request.isActive();
        if (!active) { selector.reset(); selectedSource = "phone"; handler.removeCallbacks(expire); }
        // Keep the real phone provider requested so loss of the car does not require a cold fix.
        super.onSetRequest(request);
    }
    @Override protected void onStop() {
        active = false; session = null; selector.reset(); phoneFix = null;
        selectedSource = "phone";
        handler.removeCallbacks(expire); super.onStop();
    }
    @Override public void onReportLocation(LocationResult result) {
        waitForInitialization();
        LocationResult copy = result.deepCopy();
        handler.post(() -> {
            phoneFix = new Location(copy.getLastLocation());
            if (active && !selector.useVehicle(SystemClock.elapsedRealtime())) publishPhone(copy);
        });
    }
    private void publishPhone(LocationResult result) {
        LocationResult newer = result.filter(fix -> fix.getElapsedRealtimeNanos() > publishedNanos);
        if (newer == null) return;
        LocationResult marked = newer.map(fix -> tagged(fix, "phone"));
        publishedNanos = marked.getLastLocation().getElapsedRealtimeNanos();
        selectedSource = "phone";
        reportLocation(marked);
    }
    private static Location tagged(Location fix, String source) {
        Location copy = new Location(fix);
        copy.setProvider(LocationManager.GPS_PROVIDER);
        Bundle extras = copy.getExtras() == null ? new Bundle() : new Bundle(copy.getExtras());
        extras.putString("goodman_location_source", source); copy.setExtras(extras);
        return copy;
    }
    private boolean authorized(int uid, int pid) {
        if (UserHandle.getUserId(uid) != ActivityManager.getCurrentUser()) return false;
        if (context.checkPermission(Manifest.permission.LOCATION_HARDWARE, pid, uid)
                != PackageManager.PERMISSION_GRANTED) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        return packages != null && Arrays.asList(packages).contains(PACKAGE)
                && context.getPackageManager().checkSignatures("android", PACKAGE)
                == PackageManager.SIGNATURE_MATCH;
    }
    @Override protected void onExtraCommand(int uid, int pid, String command, Bundle extras) {
        if (!"goodman.vehicle".equals(command)) { super.onExtraCommand(uid, pid, command, extras); return; }
        if (!authorized(uid, pid) || extras == null) return;
        String requestedSession = extras.getString("session");
        if (requestedSession == null || requestedSession.isEmpty() || requestedSession.length() > 64) return;
        String operation = extras.getString("operation");
        if ("begin".equals(operation)) {
            session = requestedSession; selector.reset(); handler.removeCallbacks(expire); fallback(); return;
        }
        if (!requestedSession.equals(session)) return;
        if ("end".equals(operation)) {
            session = null; selector.reset(); handler.removeCallbacks(expire); fallback(); return;
        }
        if (!"fix".equals(operation) || !active) return;
        Location fix = extras.getParcelable("location", Location.class);
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        if (fix == null || fix.isMock() || !fix.isComplete()
                || !Double.isFinite(fix.getLatitude()) || Math.abs(fix.getLatitude()) > 90
                || !Double.isFinite(fix.getLongitude()) || Math.abs(fix.getLongitude()) > 180
                || fix.getElapsedRealtimeNanos() > nowNanos
                || Math.abs(fix.getTime() - System.currentTimeMillis()) > 300000
                || nowNanos - fix.getElapsedRealtimeNanos() > GpsSelector.MAX_AGE_MS * 1000000L
                || fix.getElapsedRealtimeNanos() <= 0) return;
        if (selector.offer(fix.getElapsedRealtimeNanos() / 1000000, nowNanos / 1000000, fix.getAccuracy())) {
            if (fix.getElapsedRealtimeNanos() > publishedNanos) {
                Location output = tagged(fix, "vehicle");
                publishedNanos = output.getElapsedRealtimeNanos();
                selectedSource = "vehicle";
                reportLocation(LocationResult.wrap(output));
            }
        } else if (!selector.useVehicle(nowNanos / 1000000)) fallback();
        handler.removeCallbacks(expire);
        handler.postDelayed(expire, Math.max(1, selector.staleDeadlineMillis() - SystemClock.elapsedRealtime()));
    }
    private final Runnable expire = () -> {
        if (!selector.useVehicle(SystemClock.elapsedRealtime())) fallback();
    };
    private void fallback() {
        selectedSource = "phone";
        if (active && phoneFix != null
                && SystemClock.elapsedRealtimeNanos() - phoneFix.getElapsedRealtimeNanos()
                <= GpsSelector.MAX_AGE_MS * 1000000L) publishPhone(LocationResult.wrap(phoneFix));
        // If the cached phone fix is older than the last published car fix, wait for fresh GNSS.
    }
    @Override protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Vehicle GNSS wrapper: active=" + active + " session=" + (session != null)
                + " source=" + selectedSource);
        super.dump(fd, pw, args);
    }
}
