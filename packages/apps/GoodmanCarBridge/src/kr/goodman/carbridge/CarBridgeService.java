package kr.goodman.carbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;

public final class CarBridgeService extends Service {
    private HfpController hfp;
    private VehicleRuntime vehicle;
    private ProjectionCoordinator projection;
    private UsbProjectionController usb;
    final class LocalBinder extends Binder {
        HfpController controller() { return hfp; }
        VehicleRuntime vehicle() { return vehicle; }
        UsbProjectionController usb() { return usb; }
    }
    private final LocalBinder binder = new LocalBinder();

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("car", "차량 연결", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, CarActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "car")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Goodman Car")
                .setContentText("메인폰 HFP 연결 서비스")
                .setContentIntent(open).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        hfp = new HfpController(this);
        hfp.start();
        vehicle = new VehicleRuntime(this, hfp);
        projection = new ProjectionCoordinator(this, vehicle);
        usb = new UsbProjectionController(this, projection);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_STICKY; }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        if (usb != null) usb.close();
        if (projection != null) projection.close();
        if (vehicle != null) vehicle.close();
        if (hfp != null) hfp.close(); super.onDestroy();
    }
}
