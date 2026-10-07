package kr.goodman.carbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.nio.file.Files;
import java.io.File;
import org.json.JSONObject;

/** User-initiated update checks; flashing remains an explicit recovery operation. */
public final class RomUpdateActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private TextView status, details;
    private Button check, download;
    private RomUpdate update;
    private final Runnable poll=new Runnable() {
        public void run() {
            status.setText(getSharedPreferences("rom_update",0).getString("status","대기 중"));
            download.setEnabled(update!=null && !RomUpdateService.running);
            ui.postDelayed(this,1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int padding=(int)(20*getResources().getDisplayMetrics().density); page.setPadding(padding,padding,padding,padding);
        scroll.addView(page); setContentView(scroll);
        details=new TextView(this); details.setText("Goodman Car ROM 업데이트\n이미지를 내려받고 서명·SHA256을 확인합니다. 설치 전 데이터 백업 후 TWRP에서 시스템 이미지로 적용하세요. 기기별 설치 조건 확인이 필요합니다."); page.addView(details);
        status=new TextView(this); page.addView(status);
        check=new Button(this); check.setText("업데이트 확인"); page.addView(check);
        download=new Button(this); download.setText("검증된 이미지 다운로드"); download.setEnabled(false); page.addView(download);
        Button cancel=new Button(this); cancel.setText("다운로드 취소"); page.addView(cancel);
        check.setOnClickListener(v->check());
        download.setOnClickListener(v->{ download.setEnabled(false); startForegroundService(new Intent(this,RomUpdateService.class)); });
        cancel.setOnClickListener(v->stopService(new Intent(this,RomUpdateService.class)));
    }
    @Override protected void onStart() { super.onStart(); ui.post(poll); }
    @Override protected void onStop() { ui.removeCallbacks(poll); super.onStop(); }
    private void check() {
        if (RomUpdateService.running) return;
        check.setEnabled(false); download.setEnabled(false); update=null; show("업데이트 확인 중");
        new Thread(()->{
            try {
                JSONObject config=RomUpdate.config(getApplicationContext());
                String repo=config.getString("repository");
                if (!config.optBoolean("enabled",false)) throw new Exception("업데이트 배포 준비 중입니다. 첫 Release 게시 후 사용할 수 있습니다.");
                if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new Exception("잘못된 저장소 설정");
                String base="https://github.com/"+repo+"/releases/latest/download/";
                byte[] data=RomUpdate.get(base+"update.json",65536);
                byte[] sig=RomUpdate.get(base+"update.sig",4096);
                RomUpdate selected=RomUpdate.verify(getApplicationContext(),data,sig);
                File dir=new File(getFilesDir(),"rom-update"); dir.mkdirs();
                Files.write(new File(dir,"update.json").toPath(),data);
                Files.write(new File(dir,"update.sig").toPath(),sig);
                ui.post(()->{
                    if (isDestroyed()) return;
                    update=selected; check.setEnabled(true); download.setEnabled(true);
                    details.setText("배포 버전: "+selected.build+"\n이미지: "+String.format(java.util.Locale.ROOT,"%.2f GB",selected.bytes/1e9)+"\n"+selected.notes+"\n설치: 다운로드 완료 후 TWRP에서 시스템 이미지로 적용");
                    show("배포자 서명 확인 완료 · 현재 설치 버전보다 최신인지 변경 내용을 확인하세요.");
                });
            } catch (Exception e) {
                ui.post(()->{ if (!isDestroyed()) { check.setEnabled(true); show(e.getMessage()); } });
            }
        },"rom-update-check").start();
    }
    private void show(String message) { getSharedPreferences("rom_update",0).edit().putString("status",message).apply(); status.setText(message); }
}
