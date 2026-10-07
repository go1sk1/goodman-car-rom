package kr.goodman.carbridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;

/** Pinned publisher signature and a bounded, GitHub-only image manifest. */
final class RomUpdate {
    static final long MAX_IMAGE = 6L * 1024 * 1024 * 1024;
    final String build, notes, imageHash, repository;
    final long bytes;
    final List<Part> parts = new ArrayList<>();
    static final class Part {
        final String url, hash;
        final long bytes;
        Part(String url, String hash, long bytes) { this.url=url; this.hash=hash; this.bytes=bytes; }
    }
    private RomUpdate(JSONObject data, String repo) throws Exception {
        repository=repo;
        if (data.getInt("schema") != 1 || !"GoodmanCar-arm64-ext4".equals(data.getString("product")))
            throw new Exception("이 ROM용 업데이트가 아닙니다.");
        build=data.getString("build"); notes=data.optString("notes", "");
        if (!build.matches("[a-zA-Z0-9._-]{1,80}") || notes.length()>4096) throw new Exception("잘못된 버전 정보");
        bytes=data.getLong("bytes"); imageHash=hash(data.getString("sha256"));
        if (bytes<=0 || bytes>MAX_IMAGE || !"raw ext4".equals(data.getString("format")))
            throw new Exception("지원하지 않는 이미지 크기 또는 형식");
        JSONArray list=data.getJSONArray("parts");
        if (list.length()<1 || list.length()>8) throw new Exception("잘못된 분할 파일 수");
        long total=0;
        for (int i=0;i<list.length();i++) {
            JSONObject p=list.getJSONObject(i);
            long size=p.getLong("bytes");
            String url=p.getString("url");
            URL parsed=new URL(url);
            if (!"https".equals(parsed.getProtocol()) || !"github.com".equals(parsed.getHost())
                    || parsed.getUserInfo()!=null || parsed.getPort()!=-1
                    || !parsed.getPath().startsWith("/"+repo+"/releases/download/")
                    || parsed.getQuery()!=null || parsed.getRef()!=null || size<=0 || size>=2L*1024*1024*1024)
                throw new Exception("허용되지 않은 업데이트 파일");
            total+=size;
            parts.add(new Part(url,hash(p.getString("sha256")),size));
        }
        if (total!=bytes) throw new Exception("분할 파일 크기 불일치");
    }
    static JSONObject config(Context context) throws Exception {
        try (InputStream in=context.getAssets().open("rom-update.json")) {
            return new JSONObject(new String(read(in,65536),StandardCharsets.UTF_8));
        }
    }
    static RomUpdate verify(Context context, byte[] payload, byte[] signature) throws Exception {
        JSONObject config=config(context);
        String repo=config.getString("repository");
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new Exception("배포 저장소가 설정되지 않았습니다.");
        Signature verifier=Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(config.getString("public_key_der")))));
        verifier.update(payload);
        if (!verifier.verify(signature)) throw new Exception("업데이트 서명 확인 실패");
        return new RomUpdate(new JSONObject(new String(payload,StandardCharsets.UTF_8)),repo);
    }
    static byte[] get(String url, int limit) throws Exception {
        HttpsURLConnection connection=open(url);
        try (InputStream in=connection.getInputStream()) { return read(in,limit); }
        finally { connection.disconnect(); }
    }
    static HttpsURLConnection open(String address) throws Exception {
        for (int redirect=0;redirect<6;redirect++) {
            URL url=new URL(address);
            String host=url.getHost();
            if (!"https".equals(url.getProtocol()) || url.getUserInfo()!=null || url.getPort()!=-1
                    || !(host.equals("github.com") || host.equals("release-assets.githubusercontent.com")
                    || host.equals("objects.githubusercontent.com"))) throw new Exception("허용되지 않은 다운로드 주소");
            HttpsURLConnection c=(HttpsURLConnection)url.openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(20000); c.setReadTimeout(30000);
            c.setRequestProperty("Accept-Encoding","identity");
            int status=c.getResponseCode();
            if (status==200) return c;
            String location=c.getHeaderField("Location"); c.disconnect();
            if (!(status==301 || status==302 || status==303 || status==307 || status==308) || location==null)
                throw new Exception("다운로드 서버 응답: "+status);
            address=new URL(url,location).toString();
        }
        throw new Exception("다운로드 주소 전환이 너무 많습니다.");
    }
    static byte[] read(InputStream in,int limit) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int n;
        while ((n=in.read(buffer))!=-1) {
            if (out.size()+n>limit) throw new Exception("업데이트 정보가 너무 큽니다.");
            out.write(buffer,0,n);
        }
        return out.toByteArray();
    }
    static String hash(String value) throws Exception {
        if (!value.matches("[0-9a-f]{64}")) throw new Exception("잘못된 SHA256 정보");
        return value;
    }
    static String hex(byte[] digest) {
        StringBuilder out=new StringBuilder();
        for (byte value:digest) out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return out.toString();
    }
}
