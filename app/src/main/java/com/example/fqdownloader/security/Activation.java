package com.example.fqdownloader.security;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

public class Activation {

    private static final String TAG = "Activation";
    private static final String RAW_PATH = "https://raw.githubusercontent.com/wzmwayne/fanqie_downloader-android_rebuild/main/docs";
    private static final String PUBLIC_KEY_FILE = "/public_key.pem";
    private static final String ISSUED_FILE = "/issued.json";
    private static final String PACKAGE_NAME = "com.example.fqdownloader";
    private static final String ACTIVE_FILE = "activation.dat";
    private static final String CACHED_KEY_FILE = "cached_public_key.pem";
    private static final String CACHED_ISSUED_FILE = "cached_issued.json";

    private static final String[] PROXIES = {
        "",
        "https://gh.llkk.cc/",
        "https://github.tbedu.top/",
        "https://ghfile.geekertao.top/",
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://gh-proxy.net/",
        "https://cdn.gh-proxy.com/",
        "https://github.dpik.top/",
        "https://j.1lin.dpdns.org/",
        "https://github.starrlzy.cn/",
        "https://github-proxy.memory-echoes.cn/",
        "https://git.yylx.win/",
        "https://ghm.078465.xyz/",
        "https://gh.927223.xyz/",
        "https://gh.felicity.ac.cn/",
        "https://gh.bugdey.us.kg/",
        "https://cdn.akaere.online/",
        "https://gh.dpik.top/",
        "https://gh.inkchills.cn/",
        "https://gh.catmak.name/",
        "https://gh.b52m.cn/",
        "https://github.mxw.qzz.io/",
        "https://gh.acmsz.top/",
        "https://gh.jjj.gv.uy/",
        "https://githubdog.com/",
        "https://gh.meali.top/",
        "https://slink.ltd/",
        "https://github.tmby.shop/",
        "https://ghpr.cc/",
        "https://gh.tryxd.cn/",
        "https://gitproxy.click/",
        "https://github.chenc.dev/",
        "https://gh.ddlc.top/",
        "https://gitproxy.mrhjx.cn/",
        "https://gh.sixyin.com/",
        "https://gh.monlor.com/",
        "https://ghfast.top/",
        "https://gh.jasonzeng.dev/",
        "https://github.geekery.cn/",
        "https://fastgit.cc/",
        "https://github.ednovas.xyz/",
        "https://ghproxy.imciel.com/",
        "https://github.xxlab.tech/",
        "https://gh.idayer.com/",
        "https://gh.chjina.com/",
        "https://ghp.keleyaa.com/",
        "https://ghproxy.monkeyray.net/",
        "https://gh.noki.icu/",
    };

    public static boolean isActivated(Context ctx) {
        return new File(ctx.getFilesDir(), ACTIVE_FILE).exists();
    }

    public static boolean verify(Context ctx, String activationCode) {
        try {
            if (activationCode == null) return false;
            activationCode = activationCode.trim();
            if (activationCode.isEmpty()) return false;

            int colon = activationCode.indexOf(':');
            if (colon < 0) return false;

            String id = activationCode.substring(0, colon);
            String sigB64 = activationCode.substring(colon + 1);
            if (id.length() != 16 || sigB64.isEmpty()) return false;

            String message = PACKAGE_NAME + "||" + id;

            String publicKeyPem = fetchPublicKey(ctx);
            if (publicKeyPem == null) {
                Log.e(TAG, "无法获取公钥");
                return false;
            }

            String pem = publicKeyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");

            byte[] keyBytes = Base64.decode(pem, Base64.DEFAULT);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey pk = kf.generatePublic(spec);

            byte[] sigBytes = Base64.decode(sigB64, Base64.DEFAULT);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pk);
            sig.update(message.getBytes(StandardCharsets.UTF_8));

            if (!sig.verify(sigBytes)) {
                Log.w(TAG, "签名验证失败");
                return false;
            }

            writeFile(new File(ctx.getFilesDir(), ACTIVE_FILE), id);
            Log.i(TAG, "激活成功, ID=" + id);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "验证失败", e);
            return false;
        }
    }

    private static String fetchPublicKey(Context ctx) {
        File cached = new File(ctx.getFilesDir(), CACHED_KEY_FILE);
        if (cached.exists()) {
            try {
                return readFile(cached);
            } catch (Exception e) {
                cached.delete();
            }
        }
        String content = tryFetch(ctx, RAW_PATH + PUBLIC_KEY_FILE);
        if (content != null && content.contains("BEGIN PUBLIC KEY")) {
            try { writeFile(cached, content); } catch (Exception ignored) {}
            return content;
        }
        return null;
    }

    public static void checkAndRevoke(Context ctx) {
        new Thread(() -> {
            try {
                File actFile = new File(ctx.getFilesDir(), ACTIVE_FILE);
                if (!actFile.exists()) return;
                String savedId = readFile(actFile).trim();
                String json = fetchIssuedJson(ctx);
                if (json != null && !json.contains("\"" + savedId + "\"")) {
                    actFile.delete();
                    Log.w(TAG, "激活码 ID=" + savedId + " 已被撤销");
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private static String fetchIssuedJson(Context ctx) {
        File cached = new File(ctx.getFilesDir(), CACHED_ISSUED_FILE);
        try {
            String content = tryFetch(ctx, RAW_PATH + ISSUED_FILE);
            if (content != null) {
                writeFile(cached, content);
                return content;
            }
        } catch (Exception ignored) {}
        if (cached.exists()) {
            try { return readFile(cached); } catch (Exception ignored) {}
        }
        return null;
    }

    private static String tryFetch(Context ctx, String rawUrl) {
        List<String> urls = new ArrayList<>();
        for (String proxy : PROXIES) {
            if (proxy.isEmpty()) {
                urls.add(rawUrl);
            } else {
                urls.add(proxy + rawUrl);
            }
        }

        for (String url : urls) {
            try {
                String result = httpGet(url);
                if (result != null) return result;
            } catch (Exception e) {
                Log.d(TAG, "代理失败: " + url + " → " + e.getClass().getSimpleName());
            }
        }
        return null;
    }

    private static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("User-Agent", "FqDownloader");
        int code = conn.getResponseCode();
        if (code != 200) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        }
    }

    private static String readFile(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            fis.read(data);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static void writeFile(File f, String content) throws Exception {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
