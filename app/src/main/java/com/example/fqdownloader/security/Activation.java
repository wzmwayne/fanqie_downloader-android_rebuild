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

public class Activation {

    private static final String TAG = "Activation";
    private static final String GITHUB_RAW = "https://raw.githubusercontent.com/wzmwayne/fanqie_downloader-android_rebuild/main/docs";
    private static final String PUBLIC_KEY_URL = GITHUB_RAW + "/public_key.pem";
    private static final String ISSUED_URL = GITHUB_RAW + "/issued.json";
    private static final String PACKAGE_NAME = "com.example.fqdownloader";
    private static final String ACTIVE_FILE = "activation.dat";
    private static final String CACHED_KEY_FILE = "cached_public_key.pem";
    private static final String CACHED_ISSUED_FILE = "cached_issued.json";

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
        try {
            String content = httpGet(PUBLIC_KEY_URL);
            if (content != null && content.contains("BEGIN PUBLIC KEY")) {
                writeFile(cached, content);
                return content;
            }
        } catch (Exception e) {
            Log.w(TAG, "网络获取公钥失败", e);
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
            String content = httpGet(ISSUED_URL);
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

    private static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "FqDownloader");
        int code = conn.getResponseCode();
        if (code != 200) {
            Log.w(TAG, "HTTP " + code + " for " + urlStr);
            return null;
        }
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
