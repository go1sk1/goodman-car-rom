package kr.goodman.carbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.os.StatFs;
import android.provider.MediaStore;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import javax.net.ssl.HttpsURLConnection;

/** Foreground streaming download: no flashing, no large RAM buffers, no duplicate part files. */
public final class RomUpdateService extends Service {
    static volatile boolean running;
    private volatile boolean cancelled;
    private volatile HttpsURLConnection connection;
    private Uri pendingImage;
    private long lastNotice;
    private static final String CHANNEL="rom_update_download";
    private static final int NOTICE=4201;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"ROM 업데이트",NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTICE,notification("이미지 다운로드 준비 중"));
        if (!running) { running=true; new Thread(()->download(id),"rom-image-download").start(); }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        cancelled=true;
        HttpsURLConnection current=connection; if (current!=null) current.disconnect();
        super.onDestroy();
    }
    @Override public void onTimeout(int startId,int type) { cancelled=true; stopSelf(); }
    private void download(int id) {
        boolean complete=false;
        try {
            File dir=new File(getFilesDir(),"rom-update");
            RomUpdate update=RomUpdate.verify(this,Files.readAllBytes(new File(dir,"update.json").toPath()),
                    Files.readAllBytes(new File(dir,"update.sig").toPath()));
            if (new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes()<update.bytes+256L*1024*1024)
                throw new Exception("저장 공간이 부족합니다.");
            String filename="GoodmanCar-"+update.build+"-system.img";
            ContentValues values=new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME,filename);
            values.put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/GoodmanCar");
            values.put(MediaStore.Downloads.IS_PENDING,1);
            pendingImage=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if (pendingImage==null) throw new Exception("다운로드 파일을 만들 수 없습니다.");
            MessageDigest full=MessageDigest.getInstance("SHA-256"); long total=0;
            byte[] buffer=new byte[256*1024], superblock=new byte[1082]; int prefix=0;
            try (OutputStream out=getContentResolver().openOutputStream(pendingImage,"w")) {
                if (out==null) throw new Exception("이미지 파일을 열 수 없습니다.");
                for (RomUpdate.Part part:update.parts) {
                    ensureActive(); connection=RomUpdate.open(part.url);
                    long announced=connection.getContentLengthLong();
                    if (announced!=-1 && announced!=part.bytes) throw new Exception("서버 파일 크기 불일치");
                    MessageDigest digest=MessageDigest.getInstance("SHA-256"); long received=0;
                    try (InputStream in=connection.getInputStream()) {
                        int n;
                        while ((n=in.read(buffer))!=-1) {
                            ensureActive();
                            if (received+n>part.bytes) throw new Exception("파일 크기 초과");
                            int capture=Math.min(n,superblock.length-prefix);
                            if (capture>0) { System.arraycopy(buffer,0,superblock,prefix,capture); prefix+=capture; }
                            out.write(buffer,0,n); digest.update(buffer,0,n); full.update(buffer,0,n);
                            received+=n; total+=n;
                            if (android.os.SystemClock.elapsedRealtime()-lastNotice>1000) {
                                progress("다운로드 "+(total*100/update.bytes)+"% · "+(total/1024/1024)+" / "+(update.bytes/1024/1024)+" MB");
                                lastNotice=android.os.SystemClock.elapsedRealtime();
                            }
                        }
                    } finally { connection.disconnect(); connection=null; }
                    if (received!=part.bytes || !RomUpdate.hex(digest.digest()).equals(part.hash)) throw new Exception("분할 파일 검증 실패");
                }
                ensureActive();
                if (total!=update.bytes || !RomUpdate.hex(full.digest()).equals(update.imageHash)) throw new Exception("전체 이미지 SHA256 불일치");
                if (prefix<1082 || (superblock[1080]&255)!=0x53 || (superblock[1081]&255)!=0xef)
                    throw new Exception("EXT4 시스템 이미지가 아닙니다.");
            }
            ensureActive(); values.clear(); values.put(MediaStore.Downloads.IS_PENDING,0);
            if (getContentResolver().update(pendingImage,values,null,null)!=1) throw new Exception("다운로드 파일 저장 실패");
            complete=true;
            progress("다운로드·서명·SHA256 검증 완료\nDownload/GoodmanCar/"+filename+"\n설치는 TWRP에서 진행하세요.");
        } catch (Exception e) { progress(cancelled ? "다운로드 취소됨" : "다운로드 실패: "+e.getMessage()); }
        finally {
            if (!complete && pendingImage!=null) { try { getContentResolver().delete(pendingImage,null,null); } catch (Exception ignored) {} }
            running=false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(id);
        }
    }
    private void ensureActive() throws Exception { if (cancelled) throw new Exception("다운로드 취소됨"); }
    private void progress(String text) {
        getSharedPreferences("rom_update",0).edit().putString("status",text).apply();
        getSystemService(NotificationManager.class).notify(NOTICE,notification(text));
    }
    private Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,RomUpdateActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Goodman Car ROM 업데이트").setContentText(text).setContentIntent(open).setOngoing(true).build();
    }
}
